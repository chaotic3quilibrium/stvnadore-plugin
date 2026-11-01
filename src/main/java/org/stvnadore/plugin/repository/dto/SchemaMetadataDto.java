package org.stvnadore.plugin.repository.dto;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Wire DTO representing published STVN 2.0.0 schema metadata returned by {@code stvnadore-repository}.
 *
 * @param schemaName     nominal schema artifact identifier (including {@code .stvn_d} or {@code .stvn_bd})
 * @param domain         optional logical namespace domain declared in {@code :meta}
 * @param shapeSignature flattened structural shape signature
 * @param casHash        64-character lowercase hexadecimal SHA-256 content address
 * @param warningsCount  count of non-fatal compiler warnings reported during registration
 */
@NullMarked
public record SchemaMetadataDto(
    String schemaName,
    @Nullable String domain,
    String shapeSignature,
    String casHash,
    int warningsCount
) {}
