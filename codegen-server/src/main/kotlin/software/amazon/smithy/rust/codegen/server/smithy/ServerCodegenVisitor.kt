/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rust.codegen.server.smithy

import software.amazon.smithy.build.PluginContext
import software.amazon.smithy.model.Model
import software.amazon.smithy.model.knowledge.NullableIndex
import software.amazon.smithy.model.neighbor.Walker
import software.amazon.smithy.model.shapes.ServiceShape
import software.amazon.smithy.model.shapes.Shape
import software.amazon.smithy.model.shapes.ShapeVisitor
import software.amazon.smithy.model.shapes.StringShape
import software.amazon.smithy.model.shapes.StructureShape
import software.amazon.smithy.model.shapes.UnionShape
import software.amazon.smithy.model.traits.EnumTrait
import software.amazon.smithy.model.transform.ModelTransformer
import software.amazon.smithy.rust.codegen.client.smithy.customize.RustCodegenDecorator
import software.amazon.smithy.rust.codegen.core.rustlang.RustModule
import software.amazon.smithy.rust.codegen.core.smithy.CodegenTarget
import software.amazon.smithy.rust.codegen.core.smithy.CoreRustSettings
import software.amazon.smithy.rust.codegen.core.smithy.RustCrate
import software.amazon.smithy.rust.codegen.core.smithy.RustSymbolProvider
import software.amazon.smithy.rust.codegen.core.smithy.SymbolVisitorConfig
import software.amazon.smithy.rust.codegen.core.smithy.generators.BuilderGenerator
import software.amazon.smithy.rust.codegen.core.smithy.generators.StructureGenerator
import software.amazon.smithy.rust.codegen.core.smithy.generators.UnionGenerator
import software.amazon.smithy.rust.codegen.core.smithy.generators.implBlock
import software.amazon.smithy.rust.codegen.core.smithy.protocols.ProtocolGeneratorFactory
import software.amazon.smithy.rust.codegen.core.smithy.transformers.EventStreamNormalizer
import software.amazon.smithy.rust.codegen.core.smithy.transformers.OperationNormalizer
import software.amazon.smithy.rust.codegen.core.smithy.transformers.RecursiveShapeBoxer
import software.amazon.smithy.rust.codegen.core.util.CommandFailed
import software.amazon.smithy.rust.codegen.core.util.getTrait
import software.amazon.smithy.rust.codegen.core.util.runCommand
import software.amazon.smithy.rust.codegen.server.smithy.generators.ServerEnumGenerator
import software.amazon.smithy.rust.codegen.server.smithy.generators.ServerServiceGenerator
import software.amazon.smithy.rust.codegen.server.smithy.generators.protocol.ServerProtocol
import software.amazon.smithy.rust.codegen.server.smithy.generators.protocol.ServerProtocolGenerator
import software.amazon.smithy.rust.codegen.server.smithy.protocols.ServerProtocolLoader
import java.util.logging.Logger

val DefaultServerPublicModules = setOf(
    RustModule.Error,
    RustModule.Model,
    RustModule.Input,
    RustModule.Output,
    RustModule.Config,
).associateBy { it.name }

/**
 * Entrypoint for server-side code generation. This class will walk the in-memory model and
 * generate all the needed types by calling the accept() function on the available shapes.
 */
open class ServerCodegenVisitor(
    context: PluginContext,
    private val codegenDecorator: RustCodegenDecorator<ServerProtocolGenerator, ServerCodegenContext>,
) : ShapeVisitor.Default<Unit>() {

    protected val logger = Logger.getLogger(javaClass.name)
    protected val settings = ServerRustSettings.from(context.model, context.settings)

    protected var symbolProvider: RustSymbolProvider
    protected var rustCrate: RustCrate
    private val fileManifest = context.fileManifest
    protected var model: Model
    protected var codegenContext: ServerCodegenContext
    protected var protocolGeneratorFactory: ProtocolGeneratorFactory<ServerProtocolGenerator, ServerCodegenContext>
    protected var protocolGenerator: ServerProtocolGenerator

    init {
        val symbolVisitorConfig =
            SymbolVisitorConfig(
                runtimeConfig = settings.runtimeConfig,
                renameExceptions = false,
                nullabilityCheckMode = NullableIndex.CheckMode.SERVER,
            )
        val baseModel = baselineTransform(context.model)
        val service = settings.getService(baseModel)
        val (protocol, generator) =
            ServerProtocolLoader(
                codegenDecorator.protocols(
                    service.id,
                    ServerProtocolLoader.DefaultProtocols,
                ),
            )
                .protocolFor(context.model, service)
        protocolGeneratorFactory = generator
        model = codegenDecorator.transformModel(service, baseModel)
        symbolProvider = RustCodegenServerPlugin.baseSymbolProvider(model, service, symbolVisitorConfig)

        codegenContext = ServerCodegenContext(
            model,
            symbolProvider,
            service,
            protocol,
            settings,
        )

        rustCrate = RustCrate(context.fileManifest, symbolProvider, DefaultServerPublicModules, settings.codegenConfig)
        protocolGenerator = protocolGeneratorFactory.buildProtocolGenerator(codegenContext)
    }

    /**
     * Base model transformation applied to all services.
     * See below for details.
     */
    protected fun baselineTransform(model: Model) =
        model
            // Flattens mixins out of the model and removes them from the model
            .let { ModelTransformer.create().flattenAndRemoveMixins(it) }
            // Add errors attached at the service level to the models
            .let { ModelTransformer.create().copyServiceErrorsToOperations(it, settings.getService(it)) }
            // Add `Box<T>` to recursive shapes as necessary
            .let(RecursiveShapeBoxer::transform)
            // Normalize operations by adding synthetic input and output shapes to every operation
            .let(OperationNormalizer::transform)
            // Normalize event stream operations
            .let(EventStreamNormalizer::transform)

    /**
     * Exposure purely for unit test purposes.
     */
    internal fun baselineTransformInternalTest(model: Model) =
        baselineTransform(model)

    /**
     * Execute code generation
     *
     * 1. Load the service from [CoreRustSettings].
     * 2. Traverse every shape in the closure of the service.
     * 3. Loop through each shape and visit them (calling the override functions in this class)
     * 4. Call finalization tasks specified by decorators.
     * 5. Write the in-memory buffers out to files.
     *
     * The main work of code generation (serializers, protocols, etc.) is handled in `fn serviceShape` below.
     */
    fun execute() {
        val service = settings.getService(model)
        logger.info(
            "[rust-server-codegen] Generating Rust server for service $service, protocol ${codegenContext.protocol}",
        )
        val serviceShapes = Walker(model).walkShapes(service)
        serviceShapes.forEach { it.accept(this) }
        codegenDecorator.extras(codegenContext, rustCrate)
        rustCrate.finalize(
            settings,
            model,
            codegenDecorator.crateManifestCustomizations(codegenContext),
            codegenDecorator.libRsCustomizations(codegenContext, listOf()),
            // TODO(https://github.com/awslabs/smithy-rs/issues/1287): Remove once the server codegen is far enough along.
            requireDocs = false,
        )
        try {
            "cargo fmt".runCommand(
                fileManifest.baseDir,
                timeout = settings.codegenConfig.formatTimeoutSeconds.toLong(),
            )
        } catch (err: CommandFailed) {
            logger.warning(
                "[rust-server-codegen] Failed to run cargo fmt: [${service.id}]\n${err.output}",
            )
        }
        logger.info("[rust-server-codegen] Rust server generation complete!")
    }

    override fun getDefault(shape: Shape?) {}

    /**
     * Structure Shape Visitor
     *
     * For each structure shape, generate:
     * - A Rust structure for the shape ([StructureGenerator]).
     * - A builder for the shape.
     *
     * This function _does not_ generate any serializers.
     */
    override fun structureShape(shape: StructureShape) {
        logger.info("[rust-server-codegen] Generating a structure $shape")
        rustCrate.useShapeWriter(shape) {
            StructureGenerator(model, symbolProvider, this, shape).render(CodegenTarget.SERVER)
            val builderGenerator =
                BuilderGenerator(codegenContext.model, codegenContext.symbolProvider, shape)
            builderGenerator.render(this)
            this.implBlock(shape, symbolProvider) {
                builderGenerator.renderConvenienceMethod(this)
            }
        }
    }

    /**
     * Enum Shape Visitor
     *
     * Although raw strings require no code generation, enums are actually [EnumTrait] applied to string shapes.
     */
    override fun stringShape(shape: StringShape) {
        logger.info("[rust-server-codegen] Generating an enum $shape")
        shape.getTrait<EnumTrait>()?.also { enum ->
            rustCrate.useShapeWriter(shape) {
                ServerEnumGenerator(model, symbolProvider, this, shape, enum, codegenContext.runtimeConfig).render()
            }
        }
    }

    /**
     * Union Shape Visitor
     *
     * Generate an `enum` for union shapes.
     *
     * This function _does not_ generate any serializers.
     */
    override fun unionShape(shape: UnionShape) {
        logger.info("[rust-server-codegen] Generating an union $shape")
        rustCrate.useShapeWriter(shape) {
            UnionGenerator(model, symbolProvider, this, shape, renderUnknownVariant = false).render()
        }
    }

    /**
     * Generate service-specific code for the model:
     * - Serializers
     * - Deserializers
     * - Fluent client
     * - Trait implementations
     * - Protocol tests
     * - Operation structures
     */
    override fun serviceShape(shape: ServiceShape) {
        logger.info("[rust-server-codegen] Generating a service $shape")
        ServerServiceGenerator(
            rustCrate,
            protocolGenerator,
            protocolGeneratorFactory.support(),
            ServerProtocol.fromCoreProtocol(protocolGeneratorFactory.protocol(codegenContext)),
            codegenContext,
        )
            .render()
    }
}
