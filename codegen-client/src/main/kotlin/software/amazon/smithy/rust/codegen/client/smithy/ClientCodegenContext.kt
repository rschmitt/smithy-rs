/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rust.codegen.client.smithy

import software.amazon.smithy.model.Model
import software.amazon.smithy.model.shapes.ServiceShape
import software.amazon.smithy.model.shapes.ShapeId
import software.amazon.smithy.rust.codegen.core.smithy.CodegenContext
import software.amazon.smithy.rust.codegen.core.smithy.CodegenTarget
import software.amazon.smithy.rust.codegen.core.smithy.RustSymbolProvider

/**
 * [ClientCodegenContext] contains code-generation context that is _specific_ to the [RustCodegenPlugin] plugin
 * from the `rust-codegen` subproject.
 *
 * It inherits from [CodegenContext], which contains code-generation context that is common to _all_ smithy-rs plugins.
 */
data class ClientCodegenContext(
    override val model: Model,
    override val symbolProvider: RustSymbolProvider,
    override val serviceShape: ServiceShape,
    override val protocol: ShapeId,
    override val settings: ClientRustSettings,
) : CodegenContext(
    model, symbolProvider, serviceShape, protocol, settings, CodegenTarget.CLIENT,
)
