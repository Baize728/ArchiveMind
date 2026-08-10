package com.zyh.archivemind.eval;

public record IngestionCheckResult(
        long parsedSuccessCount,
        long documentVectorCount,
        long esDocumentCount,
        boolean dbEsConsistent,
        double contextualizedContentRate,
        double structureMetadataRate,
        double permissionMetadataRate
) {
    public static IngestionCheckResult empty() {
        return new IngestionCheckResult(0, 0, 0, false, 0.0, 0.0, 0.0);
    }
}
