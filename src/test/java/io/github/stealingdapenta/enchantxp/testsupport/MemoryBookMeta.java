package io.github.stealingdapenta.enchantxp.testsupport;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;

public final class MemoryBookMeta extends MemoryItemMeta implements EnchantmentStorageMeta {
    private final Map<Enchantment, Integer> stored = new HashMap<Enchantment, Integer>();

    @Override public boolean hasStoredEnchants() { return !stored.isEmpty(); }
    @Override public boolean hasStoredEnchant(Enchantment enchantment) { return stored.containsKey(enchantment); }
    @Override public int getStoredEnchantLevel(Enchantment enchantment) { return stored.getOrDefault(enchantment, 0); }
    @Override public Map<Enchantment, Integer> getStoredEnchants() {
        return Collections.unmodifiableMap(new HashMap<Enchantment, Integer>(stored));
    }
    @Override public boolean addStoredEnchant(Enchantment enchantment, int level, boolean ignore) {
        if (!ignore && (level < enchantment.getStartLevel() || level > enchantment.getMaxLevel())) { return false; }
        Integer previous = stored.put(enchantment, level);
        return previous == null || previous != level;
    }
    @Override public boolean removeStoredEnchant(Enchantment enchantment) { return stored.remove(enchantment) != null; }
    @Override public boolean hasConflictingStoredEnchant(Enchantment enchantment) {
        return stored.keySet().stream().anyMatch(existing -> existing.conflictsWith(enchantment));
    }
    @Override public Map<String, Object> serialize() {
        Map<String, Object> values = super.serialize();
        values.put("stored", new HashMap<Enchantment, Integer>(stored));
        return values;
    }
    @Override public MemoryBookMeta clone() {
        MemoryBookMeta copy = new MemoryBookMeta();
        copyTo(copy);
        copy.stored.putAll(stored);
        return copy;
    }
}
