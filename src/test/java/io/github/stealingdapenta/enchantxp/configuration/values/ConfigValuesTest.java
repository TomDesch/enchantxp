package io.github.stealingdapenta.enchantxp.configuration.values;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import static io.github.stealingdapenta.enchantxp.testsupport.YamlDocuments.yaml;
import static org.junit.jupiter.api.Assertions.*;

class ConfigValuesTest {
    @Test
    void requiredChildrenReportTheirPathInsteadOfReturningNullForAbsentOrMalformedSections() throws Exception {
        for (String document : new String[]{"", "enchantments:\n  DAMAGE_ALL: false\n",
                "enchantments:\n  DAMAGE_ALL: []\n"}) {
            YamlConfiguration config = yaml(document);
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> ConfigValues.requiredSection(config, "enchantments.DAMAGE_ALL"));
            assertEquals("enchantments.DAMAGE_ALL must be a section", failure.getMessage());
        }
    }
}
