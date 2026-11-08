"""Bounded, standard-library-only inspection of the upload JAR after Maven verify."""
from pathlib import Path
import re
import struct
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "io/github/stealingdapenta/enchantxp/"
MAIN = "io.github.stealingdapenta.enchantxp.EnchantXpPlugin"
FORBIDDEN_REFERENCES = (
    b"io/papermc/", b"com/destroystokyo/paper/", b"net/minecraft/",
    b"org/bukkit/craftbukkit/", b"org/bukkit/persistence/", b"java/lang/reflect/",
    b"org/junit/", b"org/mockito/", b"net/bytebuddy/", b"org/objenesis/",
)
NS = {"m": "http://maven.apache.org/POM/4.0.0"}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def inspect_jar(artifact, version):
    with zipfile.ZipFile(artifact) as jar:
        entries = jar.infolist()
        names = [entry.filename for entry in entries]
        require(len(entries) <= 512, "Too many archive entries")
        require(len(names) == len(set(names)), "Duplicate archive entries")
        require(sum(entry.file_size for entry in entries) <= 16 * 1024 * 1024, "Archive too large")
        require(all(entry.file_size <= 4 * 1024 * 1024 for entry in entries), "Oversized entry")
        for required in ("plugin.yml", "config.yml", "META-INF/LICENSE", "META-INF/maven/io.github.stealingdapenta/enchantxp/pom.xml"):
            require(required in names, "Missing " + required)
        require("paper-plugin.yml" not in names, "Paper-only metadata must not be bundled")
        classes = 0
        for name in names:
            if not name.endswith(".class"):
                continue
            require(name.startswith(PACKAGE), "Bundled third-party or test class: " + name)
            require("/testsupport/" not in name and
                    not name.rsplit("/", 1)[-1].startswith(("ServerFixture", "MemoryItemMeta", "MemoryBookMeta")) and
                    not name.endswith("Test.class"), "Bundled test class: " + name)
            data = jar.read(name)
            require(len(data) >= 8 and data[:4] == b"\xca\xfe\xba\xbe", "Invalid class: " + name)
            major = struct.unpack(">H", data[6:8])[0]
            require(major == 52, "Expected bytecode 52, got " + str(major) + ": " + name)
            require(not any(reference in data for reference in FORBIDDEN_REFERENCES), "Forbidden runtime reference: " + name)
            classes += 1
        require(classes > 0, "No plugin bytecode")
        require(MAIN.replace(".", "/") + ".class" in names, "Missing main class")
        metadata = jar.read("plugin.yml").decode("utf-8")
        require(not re.search(r"(?m)^\s*api-version\s*:", metadata), "1.12.2 must not declare api-version")
        for key, value in (("name", "EnchantXP"), ("version", version), ("main", MAIN)):
            require(re.search(r"(?m)^" + key + r":\s*" + re.escape(value) + r"\s*$", metadata), "Incorrect plugin " + key)
        embedded = ET.fromstring(jar.read("META-INF/maven/io.github.stealingdapenta/enchantxp/pom.xml"))
        require(embedded.findtext("m:version", namespaces=NS) == version, "Embedded Maven version mismatch")
        require(embedded.findtext("m:properties/m:maven.compiler.release", namespaces=NS) == "8", "Compiler release mismatch")
        properties = jar.read("META-INF/maven/io.github.stealingdapenta/enchantxp/pom.properties").decode("utf-8")
        require("version=" + version in properties, "Maven properties version mismatch")
        return classes


def main():
    pom = ET.parse(ROOT / "pom.xml").getroot()
    version = pom.findtext("m:version", namespaces=NS)
    artifacts = list((ROOT / "target").glob("enchantxp-*.jar"))
    require(len(artifacts) == 1, "Expected exactly one upload JAR: " + str(artifacts))
    artifact = artifacts[0]
    require(artifact.name == "enchantxp-" + version + ".jar", "Unexpected upload filename")
    classes = inspect_jar(artifact, version)
    print(f"PASS: {artifact} ({classes} own classes, all bytecode 52; metadata and package/reference checks passed)")


if __name__ == "__main__":
    main()
