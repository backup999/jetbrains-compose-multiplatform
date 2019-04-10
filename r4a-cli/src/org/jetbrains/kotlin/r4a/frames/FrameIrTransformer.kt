/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.r4a.frames

import org.jetbrains.kotlin.backend.common.FileLoweringPass
import org.jetbrains.kotlin.backend.common.IrElementTransformerVoidWithContext
import org.jetbrains.kotlin.backend.common.lower.createIrBuilder
import org.jetbrains.kotlin.backend.jvm.JvmBackendContext
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.descriptors.annotations.Annotations
import org.jetbrains.kotlin.descriptors.impl.ClassConstructorDescriptorImpl
import org.jetbrains.kotlin.descriptors.impl.LazyClassReceiverParameterDescriptor
import org.jetbrains.kotlin.descriptors.impl.PackageFragmentDescriptorImpl
import org.jetbrains.kotlin.descriptors.impl.PropertyDescriptorImpl
import org.jetbrains.kotlin.incremental.components.LookupLocation
import org.jetbrains.kotlin.incremental.components.NoLookupLocation
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrKtxStatement
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.builders.IrBlockBodyBuilder
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.declarations.impl.*
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetField
import org.jetbrains.kotlin.ir.expressions.IrSetField
import org.jetbrains.kotlin.ir.expressions.IrTypeOperator
import org.jetbrains.kotlin.ir.expressions.impl.*
import org.jetbrains.kotlin.ir.symbols.*
import org.jetbrains.kotlin.ir.symbols.impl.*
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.ir.visitors.IrElementTransformer
import org.jetbrains.kotlin.ir.visitors.IrElementVisitor
import org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi2ir.generators.GeneratorContext
import org.jetbrains.kotlin.r4a.frames.analysis.FrameMetadata
import org.jetbrains.kotlin.r4a.frames.analysis.FrameWritableSlices
import org.jetbrains.kotlin.r4a.frames.analysis.FrameWritableSlices.FRAMED_DESCRIPTOR
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe
import org.jetbrains.kotlin.resolve.descriptorUtil.getSuperClassNotAny
import org.jetbrains.kotlin.resolve.scopes.DescriptorKindFilter
import org.jetbrains.kotlin.resolve.scopes.MemberScope
import org.jetbrains.kotlin.resolve.scopes.MemberScopeImpl
import org.jetbrains.kotlin.storage.LockBasedStorageManager
import org.jetbrains.kotlin.types.*
import org.jetbrains.kotlin.utils.Printer

/**
 * The frame transformer extension transforms a "framed" classes properties into the form expected by the frames runtime.
 * The transformation is:
 *   - Move the backing fields for public properties from the class itself to a value record.
 *   - Change the property initializers to initialize the value record.
 *   - Change the public property getters and setters to get the current frame record and set or get the value from that
 *     record.
 *
 * The frame runtime will which value record is current for the frame.
 */
class FrameIrTransformer(val context: JvmBackendContext) :
    IrElementTransformerVoidWithContext(),
    FileLoweringPass {

    override fun lower(irFile: IrFile) {
        irFile.transformChildrenVoid(this)
    }

    override fun visitClassNew(framedClass: IrClass): IrStatement {
        val className = framedClass.descriptor.fqNameSafe
        val bindingContext = context.state.bindingContext
        val symbolTable = context.ir.symbols.externalSymbolTable

        val recordClassDescriptor =
            bindingContext.get(FrameWritableSlices.RECORD_CLASS, className) ?: return super.visitClassNew(framedClass)

        // If there are no properties, skip the class
        if (!framedClass.anyChild { it is IrProperty }) return super.visitClassNew(framedClass)

        val framesPackageDescriptor = context.state.module.getPackage(framesPackageName)

        val recordClassScope = recordClassDescriptor.unsubstitutedMemberScope

        val recordTypeDescriptor = context.state.module.findClassAcrossModuleDependencies(ClassId.topLevel(recordClassName))
            ?: error("Cannot find the Record class")

        val framedDescriptor = bindingContext.get(FRAMED_DESCRIPTOR, framedClass.descriptor.fqNameSafe)
            ?: error("Could not find framed class descriptor")

        val metadata = FrameMetadata(framedDescriptor)

        val fields = mutableListOf<IrField>()
        var recordClass: IrClass? = null


        val framedType = context.state.module.findClassAcrossModuleDependencies(ClassId.topLevel(framedTypeName))
            ?: error("Cannot find the Framed interface")

        val classBuilder = IrClassBuilder(context, framedClass, framedClass.descriptor)

        with(classBuilder) {

            fun toRecord(expr: IrExpression) = syntheticImplicitCast(
                recordTypeDescriptor.defaultType,
                recordClassDescriptor.defaultType.toIrType(),
                symbolTable.referenceClassifier(recordClassDescriptor), expr
            )

            var recordCtorSymbol: IrConstructorSymbol? = null

            // add framed state record
            +innerClass(recordClassDescriptor) { it ->
                recordClass = it
                // Create the state record constructor
                val recordConstructorDescriptor = recordClassDescriptor.unsubstitutedPrimaryConstructor
                    ?: error("Record class constructor not found")
                recordCtorSymbol = symbol(recordConstructorDescriptor)

                +constructor(recordCtorSymbol!!) {
                    // Call the ancestor constructor
                    val superConstructor =
                        symbolTable.referenceConstructor(recordClassDescriptor.getSuperClassNotAny()!!.constructors.single())

                    // Fields are left uninitialized as they will be set either by the framed object's constructor or by a call to assign()
                    +syntheticConstructorDelegatingCall(superConstructor, superConstructor.descriptor)
                }

                // Create fields corresponding to the attributes
                val attributes = recordClassScope.getVariableNames()
                for (attribute in attributes) {
                    val member = recordClassScope.getContributedVariables(
                        attribute,
                        NoLookupLocation.FROM_BACKEND
                    ).single()

                    +field(member).also { fields.add(it) }
                }

                val createMethod = recordClassScope
                    .getContributedFunctions(Name.identifier("create"), NoLookupLocation.FROM_BACKEND)
                    .single()

                +method(createMethod) {
                    // TODO(lmr): this expression seems to cause IntelliJ to freeze analysis. Comment out if you are editing this
                    // file and need analysis to work
                    +irReturn(
                        syntheticCall(
                            recordClassDescriptor.defaultType.toIrType(),
                            recordCtorSymbol!!,
                            recordConstructorDescriptor
                        )
                    )
                }

                val assignMethod = recordClassScope
                    .getContributedFunctions(Name.identifier("assign"), NoLookupLocation.FROM_BACKEND)
                    .single()

                +method(assignMethod) { irFunction ->
                    val thisParameterSymbol = this@innerClass.irClass.thisReceiver!!.symbol
                    val valueParameterSymbol = irFunction.valueParameters[0].symbol
                    for (i in 0 until attributes.count()) {
                        val field = fields[i]
                        val thisParameter = syntheticGetValue(thisParameterSymbol)
                        val valueParameter = toRecord(syntheticGetValue(valueParameterSymbol))
                        val otherField = syntheticGetField(field, valueParameter)

                        +syntheticSetField(field, thisParameter, otherField)
                    }
                }

            }

            // augment this class with the Framed interface
            addInterface(framedType)

            val thisSymbol = framedClass.thisReceiver?.symbol ?: error("No this receiver found for class ${framedClass.name}")
            val thisValue = syntheticGetValue(thisSymbol)

            val recordPropertyDescriptor = PropertyDescriptorImpl.create(
                /* containingDeclaration = */ framedDescriptor,
                /* annotations           = */ Annotations.EMPTY,
                /* modality              = */ Modality.FINAL,
                /* visibility            = */ Visibilities.PRIVATE,
                /* isVar                 = */ true,
                /* name                  = */ Name.identifier("\$record"),
                /* kind                  = */ CallableMemberDescriptor.Kind.DECLARATION,
                /* source                = */ SourceElement.NO_SOURCE,
                /* lateInit              = */ false,
                /* isConst               = */ false,
                /* isExpect              = */ false,
                /* isActual              = */ false,
                /* isExternal            = */ false,
                /* isDelegated           = */ false
            ).apply {
                initialize(null, null)

                setType(recordTypeDescriptor.defaultType, emptyList(), recordTypeDescriptor.thisAsReceiverParameter, null)
            }

            val fieldReference = field(recordPropertyDescriptor)

            +fieldReference

            +method(metadata.firstFrameDescriptor(recordTypeDescriptor)) {
                val dispatchReceiver = syntheticGetValue(it.dispatchReceiverParameter!!.symbol)

                // TODO(lmr): this expression seems to cause IntelliJ to freeze analysis. Comment out if you are editing this
                // file and need analysis to work
                +irReturn(syntheticGetField(fieldReference, dispatchReceiver))
            }

            +method(metadata.prependFrameRecordDescriptor(recordTypeDescriptor)) {
                val dispatchReceiver = syntheticGetValue(it.dispatchReceiverParameter!!.symbol)

                val nextField = recordTypeDescriptor.unsubstitutedMemberScope.getContributedVariables(
                    Name.identifier("next"),
                    NoLookupLocation.FROM_BACKEND
                ).singleOrNull() ?: error("Could not find Record.next field")

                val setNextSymbol = symbolTable.referenceFunction(nextField.setter ?: error("Expected setter for Record.next"))
                val valueParameter = it.valueParameters[0]
                val valueParameterSymbol = valueParameter.symbol
                val value = syntheticGetValue(valueParameterSymbol)

                +syntheticSetterCall(
                    setNextSymbol,
                    nextField.setter!!,
                    value,
                    syntheticGetField(fieldReference, dispatchReceiver)
                )
                +syntheticSetField(
                    fieldReference,
                    dispatchReceiver,
                    value
                )
            }

            val recordGetter = syntheticGetField(fieldReference, thisValue)

            fun getRecord() = toRecord(recordGetter)

            +initializer {
                // Create the initial state record
                +syntheticSetField(
                    fieldReference, thisValue, syntheticCall(
                        recordClassDescriptor.defaultType.toIrType()!!,
                        recordCtorSymbol!!,
                        // Non-null was already validated when the record class was constructed
                        recordClassDescriptor.unsubstitutedPrimaryConstructor!!
                    )
                )

                // Assign the fields
                metadata.getFramedProperties(bindingContext).forEach { propertyDescriptor ->
                    // Move backing field initializer to an anonymous initializer of the record field
                    val irFramedProperty = framedClass.declarations.find { it.descriptor.name == propertyDescriptor.name } as? IrProperty
                        ?: error("Could not find ir representation of ${propertyDescriptor.name}")
                    val irRecordField =
                        fields.find { it.name == propertyDescriptor.name } ?: error("Could not find record field for $className")
                    val backingField = irFramedProperty.backingField ?: TODO("Properties without a backing field are not supported yet")
                    backingField.initializer?.let { initializer ->
                        // (this.next as <record>).<field> = <initializer>
                        +syntheticSetField(irRecordField, getRecord(), initializer.expression)
                    }
                }

                // TODO(lmr): this expression seems to cause IntelliJ to freeze analysis. Comment out if you are editing this
                // file and need analysis to work
                val unitType: IrType? = context.irBuiltIns.unitType

                // Notify the runtime a new framed object was created
                val createdDescriptor = framesPackageDescriptor.memberScope.getContributedFunctions(
                    Name.identifier("_created"),
                    NoLookupLocation.FROM_BACKEND
                ).single()

                +syntheticCall(unitType!!, symbolTable.referenceSimpleFunction(createdDescriptor), createdDescriptor).apply {
                    putValueArgument(0, thisValue)
                }

                // TODO(http://b/79588393): Determine if the order is important here. Should this be added before, all other initializers, after, be before the property
            }

            // Replace property getter/setters with _readable/_writable calls (this, indirectly, removes the backing field)
            val readableDescriptor =
                framesPackageDescriptor.memberScope.getContributedFunctions(
                    Name.identifier("_readable"),
                    NoLookupLocation.FROM_BACKEND
                ).single()
            val writableDescriptor =
                framesPackageDescriptor.memberScope.getContributedFunctions(
                    Name.identifier("_writable"),
                    NoLookupLocation.FROM_BACKEND
                ).single()
            val readableSymbol = symbolTable.referenceSimpleFunction(readableDescriptor)
            val writableSymbol = symbolTable.referenceSimpleFunction(writableDescriptor)
            metadata.getFramedProperties(context.state.bindingContext).forEach { propertyDescriptor ->
                val irFramedProperty = framedClass.declarations.find { it.descriptor.name == propertyDescriptor.name } as? IrProperty
                    ?: error("Could not find ir representation of ${propertyDescriptor.name}")
                val irRecordField = fields.find { it.name == propertyDescriptor.name }
                    ?: error("Could not find record field of ${propertyDescriptor.name}")
                irFramedProperty.backingField = null
                irFramedProperty.getter?.let { getter ->
                    // replace this.field with (_readable(this.next) as <record>).<field>
                    getter.origin = IrDeclarationOrigin.DEFINED
                    getter.body?.transform(object : IrElementTransformer<Nothing?> {
                        override fun visitGetField(expression: IrGetField, data: Nothing?): IrExpression {
                            val newExpression = if (expression.descriptor == irFramedProperty.descriptor) {
                                syntheticGetField(irRecordField,
                                                  toRecord(syntheticCall(
                                                      recordClass!!.defaultType,
                                                      readableSymbol,
                                                      readableDescriptor
                                                  ).also {
                                                      it.putValueArgument(
                                                          0,
                                                          recordGetter
                                                      )
                                                      it.putValueArgument(1, syntheticGetValue(thisSymbol))
                                                  }
                                                  ))

                            } else expression
                            return super.visitGetField(newExpression, data)
                        }
                    }, null)
                }

                irFramedProperty.setter?.let { setter ->
                    setter.origin = IrDeclarationOrigin.DEFINED
                    // replace "this.field = value" with "(_writable(this.next) as <record>).<field> = value"
                    setter.body?.transform(object : IrElementTransformer<Nothing?> {
                        override fun visitSetField(expression: IrSetField, data: Nothing?): IrExpression {
                            val newExpression = if (expression.descriptor == irFramedProperty.descriptor) {
                                syntheticSetField(
                                    irRecordField,
                                    toRecord(syntheticCall(
                                        recordClass!!.defaultType,
                                        writableSymbol,
                                        writableDescriptor
                                    ).also {
                                        it.putValueArgument(
                                            0, recordGetter
                                        )
                                        it.putValueArgument(1, syntheticGetValue(thisSymbol))
                                    }),
                                    expression.value
                                )
                            } else expression
                            return super.visitSetField(newExpression, data)
                        }
                    }, null)
                }
            }

        }

        return super.visitClassNew(classBuilder.irClass)
    }

}

fun augmentInterfaceList(original: ClassDescriptor, addedInterface: ClassDescriptor): ClassDescriptor =
    object : ClassDescriptor by original {
        override fun getTypeConstructor(): TypeConstructor = object : TypeConstructor by original.typeConstructor {
            override fun getSupertypes(): Collection<KotlinType> =
                original.typeConstructor.supertypes + addedInterface.defaultType
        }
    }

fun augmentClassWithInterface(irClass: IrClass, interfaceDescriptor: ClassDescriptor): IrClass = object : IrClass by irClass {
    val newDescriptor by lazy {
        augmentInterfaceList(irClass.descriptor, interfaceDescriptor)
    }

    override val descriptor: ClassDescriptor get() = newDescriptor

    override fun <R, D> accept(visitor: IrElementVisitor<R, D>, data: D): R {
        val result = irClass.accept(visitor, data)
        @Suppress("UNCHECKED_CAST")
        return if (result === irClass) this as R else result
    }

    override fun <D> transform(transformer: IrElementTransformer<D>, data: D): IrStatement {
        val result = irClass.transform(transformer, data)
        if (result == irClass) return this
        return result
    }
}

// TODO(chuckj): This is copied from R4ASyntheticExtension. Consider moving it to a location that can be shared.
fun IrElement.find(filter: (descriptor: IrElement) -> Boolean): Collection<IrElement> {
    val elements = mutableListOf<IrElement>()
    accept(object : IrElementVisitorVoid {
        override fun visitElement(element: IrElement) {
            if (filter(element)) elements.add(element)
            element.acceptChildren(this, null)
        }

        override fun visitKtxStatement(expression: IrKtxStatement, data: Nothing?) {
            expression.acceptChildren(this, null)
        }
    }, null)
    return elements
}

fun IrElement.anyChild(filter: (descriptor: IrElement) -> Boolean): Boolean {
    var result = false
    accept(object : IrElementVisitorVoid {
        override fun visitElement(element: IrElement) {
            if (!result) {
                if (filter(element)) {
                    result = true
                } else {
                    element.acceptChildren(this, null)
                }
            }
        }

        override fun visitKtxStatement(expression: IrKtxStatement, data: Nothing?) {
            if (!result)
                expression.acceptChildren(this, null)
        }
    }, null)
    return result
}

class IrClassBuilder(private val context: JvmBackendContext, var irClass: IrClass, private val classDescriptor: ClassDescriptor) {
    private val typeTranslator =
        TypeTranslator(context.ir.symbols.externalSymbolTable, context.state.languageVersionSettings, context.builtIns).apply {
            constantValueGenerator = ConstantValueGenerator(context.state.module, context.ir.symbols.externalSymbolTable)
            constantValueGenerator.typeTranslator = this
        }

    operator fun IrDeclaration.unaryPlus() {
        parent = irClass
        irClass.declarations.add(this)
    }

    fun addInterface(interfaceDescriptor: ClassDescriptor) {
        irClass = augmentClassWithInterface(irClass, interfaceDescriptor)
    }

    fun initializer(block: IrBlockBodyBuilder.(IrAnonymousInitializer) -> Unit): IrAnonymousInitializer {
        val initializerSymbol = IrAnonymousInitializerSymbolImpl(classDescriptor)
        return IrAnonymousInitializerImpl(
            UNDEFINED_OFFSET, UNDEFINED_OFFSET,
            IrDeclarationOrigin.DELEGATE,
            initializerSymbol
        ).apply {
            body = context.createIrBuilder(initializerSymbol).irBlockBody {
                this@irBlockBody.block(this@apply)
            }
        }
    }

    fun innerClass(
        classDescriptor: ClassDescriptor,
        block: IrClassBuilder.(IrClass) -> Unit
    ) = innerClass(IrClassSymbolImpl(classDescriptor), classDescriptor.thisAsReceiverParameter, block)

    fun innerClass(
        classSymbol: IrClassSymbol,
        receiverParameterDescriptor: ReceiverParameterDescriptor,
        block: IrClassBuilder.(IrClass) -> Unit
    ) = IrClassImpl(
        UNDEFINED_OFFSET,
        UNDEFINED_OFFSET,
        IrDeclarationOrigin.DELEGATE,
        classSymbol
    ).also {
        it.thisReceiver = syntheticValueParameter(receiverParameterDescriptor)
        IrClassBuilder(context, it, it.descriptor).block(it)
    }

    fun constructor(descriptor: ClassConstructorDescriptor, block: IrBlockBodyBuilder.(IrConstructor) -> Unit) =
        constructor(symbol(descriptor), block)

    fun constructor(constructorSymbol: IrConstructorSymbol, block: IrBlockBodyBuilder.(IrConstructor) -> Unit): IrConstructor {
        return IrConstructorImpl(
            UNDEFINED_OFFSET, UNDEFINED_OFFSET,
            IrDeclarationOrigin.DELEGATE,
            constructorSymbol,
            irClass.defaultType
        ).apply {
            body = context.createIrBuilder(constructorSymbol).irBlockBody {
                createParameterDeclarations()
                this@irBlockBody.block(this@apply)
            }
        }
    }


    fun IrFunction.createParameterDeclarations() {
        fun ParameterDescriptor.irValueParameter() = IrValueParameterImpl(
            UNDEFINED_OFFSET, UNDEFINED_OFFSET,
            IrDeclarationOrigin.DEFINED,
            this,
            type.toIrType(),
            (this as? ValueParameterDescriptor)?.varargElementType?.toIrType()
        ).also {
            it.parent = this@createParameterDeclarations
        }

        dispatchReceiverParameter = descriptor.dispatchReceiverParameter?.irValueParameter()
        extensionReceiverParameter = descriptor.extensionReceiverParameter?.irValueParameter()

        assert(valueParameters.isEmpty())
        descriptor.valueParameters.mapTo(valueParameters) { it.irValueParameter() }

        assert(typeParameters.isEmpty())
        descriptor.typeParameters.mapTo(typeParameters) {
            IrTypeParameterImpl(
                UNDEFINED_OFFSET, UNDEFINED_OFFSET,
                IrDeclarationOrigin.DEFINED,
                it
            ).also { typeParameter ->
                typeParameter.parent = this
            }
        }
    }

    fun syntheticGetField(field: IrField, receiver: IrExpression) =
        IrGetFieldImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, field.symbol, field.type, receiver)

    fun syntheticSetField(field: IrField, receiver: IrExpression, expression: IrExpression) =
        IrSetFieldImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, field.symbol, receiver, expression, field.type)

    fun symbol(member: PropertyDescriptor) = IrFieldSymbolImpl(member)
    fun symbol(method: FunctionDescriptor) = IrSimpleFunctionSymbolImpl(method)
    fun symbol(constructor: ClassConstructorDescriptor) = IrConstructorSymbolImpl(constructor)

    fun field(member: PropertyDescriptor) = IrFieldImpl(
        UNDEFINED_OFFSET, UNDEFINED_OFFSET,
        IrDeclarationOrigin.DELEGATE,
        member,
        member.type.toIrType()
    )

    fun KotlinType.toIrType() = typeTranslator.translateType(this)

    fun method(
        methodDescriptor: FunctionDescriptor,
        returnType: IrType? = null,
        block: IrBlockBodyBuilder.(IrSimpleFunction) -> Unit
    ): IrFunction {
        val realReturnType = returnType ?: methodDescriptor.returnType?.toIrType()

        return IrFunctionImpl(
            UNDEFINED_OFFSET, UNDEFINED_OFFSET,
            IrDeclarationOrigin.DELEGATE,
            methodDescriptor,
            realReturnType ?: context.irBuiltIns.unitType
        ).apply {
            body = context.createIrBuilder(symbol).irBlockBody {
                createParameterDeclarations()
                this@irBlockBody.block(this@apply)
            }
        }
    }

    fun syntheticSetterCall(
        symbol: IrFunctionSymbol,
        descriptor: FunctionDescriptor,
        dispatchReceiver: IrExpression?,
        argument: IrExpression
    ) = IrSetterCallImpl(
        UNDEFINED_OFFSET, UNDEFINED_OFFSET, descriptor.returnType!!.toIrType(), symbol, descriptor,
        typeArgumentsCount = 0,
        dispatchReceiver = dispatchReceiver,
        extensionReceiver = null,
        argument = argument
    )

    fun syntheticValueParameter(parameter: ParameterDescriptor) = IrValueParameterImpl(
        UNDEFINED_OFFSET, UNDEFINED_OFFSET,
        IrDeclarationOrigin.DELEGATE,
        parameter,
        parameter.type.toIrType(),
        null
    )

    fun syntheticCall(kotlinType: IrType, symbol: IrFunctionSymbol, descriptor: FunctionDescriptor) =
        IrCallImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, kotlinType, symbol, descriptor, typeArgumentsCount = 0)

    fun syntheticGetValue(symbol: IrValueSymbol) =
        IrGetValueImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, symbol)

    fun syntheticImplicitCast(fromType: KotlinType, toType: IrType, classifier: IrClassifierSymbol, argument: IrExpression) =
        IrTypeOperatorCallImpl(
            UNDEFINED_OFFSET, UNDEFINED_OFFSET, fromType.toIrType(), IrTypeOperator.IMPLICIT_CAST,
            toType, classifier, argument
        )
}

fun IrBlockBodyBuilder.syntheticConstructorDelegatingCall(symbol: IrConstructorSymbol, descriptor: ClassConstructorDescriptor) =
    IrDelegatingConstructorCallImpl(
        UNDEFINED_OFFSET,
        UNDEFINED_OFFSET,
        context.irBuiltIns.unitType,
        symbol,
        descriptor,
        typeArgumentsCount = 0
    )

inline fun <T : IrDeclaration> T.buildWithScope(context: GeneratorContext, crossinline builder: (T) -> Unit): T =
    also { irDeclaration ->
        context.symbolTable.withScope(irDeclaration.descriptor) {
            builder(irDeclaration)
        }
    }

inline fun <T, D : DeclarationDescriptor> ReferenceSymbolTable.withScope(owner: D, block: ReferenceSymbolTable.(D) -> T): T {
    enterScope(owner)
    val result = block(owner)
    leaveScope(owner)
    return result
}

val IrClass.containingFile: IrFile?
    get() {
        var node: IrDeclarationParent? = parent
        while (node != null) {
            if (node is IrFile) return node
            node = (node as? IrDeclaration)?.parent
        }
        return null
    }

class SyntheticFramePackageDescriptor(module: ModuleDescriptor, fqName: FqName) : PackageFragmentDescriptorImpl(module, fqName) {
    private lateinit var classDescriptor: ClassDescriptor
    private val scope: MemberScope = object : MemberScopeImpl() {
        override fun getContributedDescriptors(
            kindFilter: DescriptorKindFilter,
            nameFilter: (Name) -> Boolean
        ): List<DeclarationDescriptor> =
            if (nameFilter(classDescriptor.name)) listOf(classDescriptor) else emptyList()

        override fun getContributedClassifier(name: Name, location: LookupLocation): ClassifierDescriptor? =
            if (classDescriptor.fqNameSafe == FqName(fqName.toString() + "" + name.identifier)) classDescriptor else null

        override fun printScopeStructure(p: Printer) {
            p.println(this::class.java.simpleName)
        }
    }

    override fun getMemberScope() = scope

    fun setClassDescriptor(value: ClassDescriptor) {
        classDescriptor = value
    }
}

// TODO(chuckj): Consider refactoring to have a shared synthetic base with other synthetic classes
class FrameRecordClassDescriptor(
    private val myName: Name,
    private val myContainingDeclaration: DeclarationDescriptor,
    val recordDescriptor: ClassDescriptor,
    myFramedClassDescriptor: ClassDescriptor,
    mySuperTypes: Collection<KotlinType>,
    bindingContext: BindingContext
) : ClassDescriptor {
    override fun getKind() = ClassKind.CLASS
    override fun getModality() = Modality.FINAL
    override fun getName() = myName
    override fun getSource() = SourceElement.NO_SOURCE!!
    override fun getMemberScope(typeArguments: MutableList<out TypeProjection>): MemberScope = myScope
    override fun getMemberScope(typeSubstitution: TypeSubstitution): MemberScope = myScope
    override fun getUnsubstitutedMemberScope(): MemberScope = myScope
    override fun getUnsubstitutedInnerClassesScope(): MemberScope = myScope
    override fun getStaticScope(): MemberScope = myScope
    override fun getConstructors(): Collection<ClassConstructorDescriptor> = listOf(myUnsubstitutedPrimaryConstructor)
    override fun getContainingDeclaration() = myContainingDeclaration
    override fun getDefaultType() = myDefaultType
    override fun getCompanionObjectDescriptor(): ClassDescriptor? = null
    override fun getVisibility() = Visibilities.PUBLIC
    override fun isCompanionObject() = false
    override fun isData(): Boolean = false
    override fun isInline() = false
    override fun getThisAsReceiverParameter() = thisAsReceiverParameter
    override fun getUnsubstitutedPrimaryConstructor(): ClassConstructorDescriptor? = myUnsubstitutedPrimaryConstructor
    override fun getSealedSubclasses(): Collection<ClassDescriptor> = emptyList()
    override fun getOriginal(): ClassDescriptor = this
    override fun isExpect() = false
    override fun substitute(substitutor: TypeSubstitutor): ClassifierDescriptorWithTypeParameters =
        error("Class $this can't be substituted")

    override fun isActual(): Boolean = false
    override fun getTypeConstructor() = myTypeConstructor
    override fun isInner() = false

    override fun <R : Any?, D : Any?> accept(visitor: DeclarationDescriptorVisitor<R, D>?, data: D): R {
        return visitor!!.visitClassDescriptor(this, data)
    }

    override fun getDeclaredTypeParameters() = emptyList<TypeParameterDescriptor>()

    override fun isExternal() = false

    override fun acceptVoid(visitor: DeclarationDescriptorVisitor<Void, Void>?) {
        visitor?.visitClassDescriptor(this, null)
    }

    override val annotations = Annotations.EMPTY

    private val myUnsubstitutedPrimaryConstructor: ClassConstructorDescriptor by lazy {
        val constructor = ClassConstructorDescriptorImpl.create(
            this,
            Annotations.EMPTY,
            false,
            SourceElement.NO_SOURCE
        )
        constructor.initialize(
            emptyList(),
            Visibilities.PUBLIC
        )

        constructor.apply {
            returnType = containingDeclaration.defaultType
        }
        constructor as ClassConstructorDescriptor
    }

    private val myScope = genScope(bindingContext)

    private val myDefaultType: SimpleType by lazy {
        TypeUtils.makeUnsubstitutedType(this, unsubstitutedMemberScope)
    }

    private val thisAsReceiverParameter = LazyClassReceiverParameterDescriptor(this)
    private val myTypeConstructor = ClassTypeConstructorImpl(this, declaredTypeParameters, mySuperTypes, LockBasedStorageManager.NO_LOCKS)
    private val myMetadata = FrameMetadata(myFramedClassDescriptor)

    private fun genScope(bindingContext: BindingContext): MemberScope {
        return object : MemberScopeImpl() {

            override fun printScopeStructure(p: Printer) {
                p.println(this::class.java.simpleName)
            }

            override fun getVariableNames() = myVariableNames
            override fun getContributedVariables(name: Name, location: LookupLocation): Collection<PropertyDescriptor> =
                myContributedVariables[name]?.let { listOf(it) } ?: emptyList()

            override fun getFunctionNames(): Set<Name> = myFunctionNames
            override fun getContributedFunctions(name: Name, location: LookupLocation): Collection<SimpleFunctionDescriptor> =
                myContributedFunctions[name]?.let { listOf(it) } ?: emptyList()

            val myVariableNames by lazy { myContributedVariables.keys }
            val myContributedVariables by lazy {
                myMetadata.getRecordPropertyDescriptors(this@FrameRecordClassDescriptor, bindingContext).map {
                    it.name to it
                }.toMap()
            }
            val myFunctionNames by lazy { myContributedFunctions.keys }
            val myContributedFunctions: Map<Name, SimpleFunctionDescriptor> by lazy {
                myMetadata.getRecordMethodDescriptors(this@FrameRecordClassDescriptor, recordDescriptor).map {
                    it.name to it
                }.toMap()
            }
        }
    }
}
