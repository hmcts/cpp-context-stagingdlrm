package uk.gov.moj.cpp.stagingdlrm.event.processor.domain;

import uk.gov.moj.cpp.stagingdlrm.json.schemas.Channel;
import uk.gov.moj.cpp.stagingdlrm.migrated.json.schemas.MigratedCase;
import uk.gov.moj.cpp.stagingdlrm.migrated.json.schemas.MigratedMaterial;

import java.io.Serializable;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonCreator;

public class ReceiveMigratedCaseFile implements Serializable {

    private final List<MigratedMaterial> materials;

    private final MigratedCase migratedCase;

    private final UUID submissionId;

    private final Channel channel;

    @JsonCreator
    public ReceiveMigratedCaseFile(final List<MigratedMaterial> materials, final MigratedCase migratedCase, final UUID submissionId, final Channel channel) {
        this.materials = materials;
        this.migratedCase = migratedCase;
        this.submissionId = submissionId;
        this.channel = channel;
    }

    public List<MigratedMaterial> getMaterials() {
        return materials;
    }

    public MigratedCase getMigratedCase() {
        return migratedCase;
    }

    public UUID getSubmissionId() {
        return submissionId;
    }

    public Channel getChannel() {
        return channel;
    }
}
