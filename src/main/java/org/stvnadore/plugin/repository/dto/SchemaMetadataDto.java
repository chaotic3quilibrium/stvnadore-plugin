package org.stvnadore.plugin.repository.dto;

import org.jspecify.annotations.NullMarked;

/**
 * Metadata DTO returned upon successful schema registration.
 *
 * @param schemaName nominal schema identifier
 * @param shapeSignature flattened structural shape signature
 * @param casHash 64-character SHA-256 content address
 */
@NullMarked
public record SchemaMetadataDto(String schemaName, String shapeSignature, String casHash) {}