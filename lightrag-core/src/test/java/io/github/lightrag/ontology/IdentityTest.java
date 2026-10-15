package io.github.lightrag.ontology;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdentityTest {

    @Test
    void identityIdNormalizesCaseAndWhitespace() {
        assertThat(Identity.identityId("  Person  ")).isEqualTo("person");
        assertThat(Identity.identityId("PERSON")).isEqualTo("person");
        assertThat(Identity.identityId("  PeRSoN  ")).isEqualTo("person");
    }

    @Test
    void identityIdCollapsesInternalWhitespace() {
        assertThat(Identity.identityId("Person   Name")).isEqualTo("person name");
        assertThat(Identity.identityId("Person\t\tName")).isEqualTo("person name");
        assertThat(Identity.identityId("Person\nName")).isEqualTo("person name");
    }

    @Test
    void identityIdAppliesNfcNormalization() {
        String nfd = "e\u0301"; // e + combining acute
        String nfc = "\u00e9";  // precomposed e-acute
        assertThat(Identity.identityId(nfd)).isEqualTo(nfc.toLowerCase());
        assertThat(Identity.identityId(nfd)).isEqualTo(Identity.identityId(nfc));
    }

    @Test
    void identityIdRejectsNull() {
        assertThatThrownBy(() -> Identity.identityId(null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void identityIdRejectsBlank() {
        assertThatThrownBy(() -> Identity.identityId("   "))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("blank");
    }

    @Test
    void identityIdUsesRootLocaleForTurkishI() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("tr", "TR"));
            assertThat(Identity.identityId("TITLE")).isEqualTo("title");
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    void identityIdCollapsesUnicodeWhitespace() {
        assertThat(Identity.identityId("Person\u3000Name")).isEqualTo("person name");
        assertThat(Identity.identityId("\u00A0Person\u00A0")).isEqualTo("person");
        assertThat(Identity.identityId("\u202FPerson\u202F")).isEqualTo("person");
        assertThat(Identity.identityId("\u2007Person\u2007")).isEqualTo("person");
    }

    @Test
    void identityIdIsIdempotent() {
        String first = Identity.identityId("  Person\u00A0Name  ");
        assertThat(Identity.identityId(first)).isEqualTo(first);
    }

    @Test
    void identityIdRejectsPureUnicodeWhitespace() {
        assertThatThrownBy(() -> Identity.identityId("\u00A0"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("blank");
        assertThatThrownBy(() -> Identity.identityId("\u202F"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Identity.identityId("\u2007"))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
