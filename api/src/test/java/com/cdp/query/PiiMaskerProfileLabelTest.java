package com.cdp.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.cdp.engine.model.EnrollmentStatus;
import com.cdp.profile.IdentifierType;
import com.cdp.profile.IdentifierView;

class PiiMaskerProfileLabelTest {

    private static EnrollmentSummary summary(String name, IdentifierView id) {
        Instant t = Instant.parse("2026-11-10T02:00:00Z");
        return new EnrollmentSummary(
                "e", "j", 1, "n", "p", EnrollmentStatus.ACTIVE, "s", null, null, t, t, null, name, id);
    }

    private static PiiMasker masker(Set<String> keys) {
        return new PiiMasker(new MaskingProperties(keys));
    }

    @Test
    void defaultShowsNameAndMasksEmailAndPhone() {
        PiiMasker m = masker(null);
        EnrollmentSummary email =
                m.maskSummary(summary("Amy", new IdentifierView(IdentifierType.EMAIL, "amy@x.com", false)));
        assertThat(email.profileName()).isEqualTo("Amy");
        assertThat(email.profileIdentifier()).isEqualTo(new IdentifierView(IdentifierType.EMAIL, "a***@x.com", true));
        EnrollmentSummary phone =
                m.maskSummary(summary(null, new IdentifierView(IdentifierType.PHONE, "+886912345678", false)));
        assertThat(phone.profileName()).isNull();
        assertThat(phone.profileIdentifier().value()).isEqualTo("***");
    }

    @Test
    void dashHidesNameAndNullIdentifierStaysNull() {
        EnrollmentSummary out = masker(Set.of("-")).maskSummary(summary("Amy", null));
        assertThat(out.profileName()).isNull();
        assertThat(out.profileIdentifier()).isNull();
    }

    @Test
    void whitelistedIdentifierTypeIsPlain() {
        EnrollmentSummary out =
                masker(Set.of("member_id"))
                        .maskSummary(summary("Amy", new IdentifierView(IdentifierType.MEMBER_ID, "M-1", false)));
        assertThat(out.profileName()).isNull();
        assertThat(out.profileIdentifier()).isEqualTo(new IdentifierView(IdentifierType.MEMBER_ID, "M-1", false));
    }
}
