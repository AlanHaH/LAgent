package com.adaptivelearning.profile.application;

import com.adaptivelearning.profile.domain.ProfileGenerationJobEntity;
import com.adaptivelearning.profile.domain.ProfileVersionEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProfileIdSerializationTest {
    private static final long LARGE_ID = 9_007_199_254_740_993L;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @Test
    void serializesBrowserVisibleProfileIdsAsStrings() throws Exception {
        ProfileVersionEntity version = new ProfileVersionEntity();
        version.setId(LARGE_ID);
        version.setProfileId(LARGE_ID + 1);
        version.setCreatedBy(LARGE_ID + 2);
        var versionJson = json.valueToTree(version);

        assertThat(versionJson.path("id").asText()).isEqualTo("9007199254740993");
        assertThat(versionJson.path("id").isTextual()).isTrue();
        assertThat(versionJson.path("profileId").isTextual()).isTrue();
        assertThat(versionJson.path("createdBy").isTextual()).isTrue();

        ProfileGenerationJobEntity job = new ProfileGenerationJobEntity();
        job.setId(LARGE_ID);
        job.setUserId(LARGE_ID + 1);
        job.setProfileVersionId(LARGE_ID + 2);
        var jobJson = json.valueToTree(job);

        assertThat(jobJson.path("id").asText()).isEqualTo("9007199254740993");
        assertThat(jobJson.path("userId").isTextual()).isTrue();
        assertThat(jobJson.path("profileVersionId").isTextual()).isTrue();
    }
}
