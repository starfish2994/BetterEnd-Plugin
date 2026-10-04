package org.betterx.betterend.bukkit.advancement;

import com.fren_gor.ultimateAdvancementAPI.advancement.display.AdvancementDisplay;
import com.fren_gor.ultimateAdvancementAPI.advancement.display.AdvancementFrameType;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TranslatableComponent;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * An advancement display whose title and description are translation KEYS, resolved by each client
 * against the CraftEngine resource pack rather than baked into the packet as English.
 * <p>
 * The keys are the mod's own -- {@code advancements.betterend.<id>.title} and
 * {@code .description} -- and the pack ships their strings in
 * {@code configuration/langs/b9_en_us.yml}, so a player running a Chinese client gets Chinese the
 * moment someone adds a {@code zh_cn} section, with no server change.
 * <p>
 * The fork calls {@link #usesComponentDisplay()} in three places (the NMS display wrapper, the chat
 * announcement and the toast) and only then reads {@link #getChatTitle()} / {@link
 * #getChatDescription()}. There is no {@code @Override} on it deliberately: the port compiles
 * against the fork's jar, but the method is absent from upstream UltimateAdvancementAPI, and an
 * {@code @Override} would make a downgrade to upstream a compile error instead of a silently
 * untranslated tooltip.
 */
final class LocalizedDisplay extends AdvancementDisplay {
    private final String titleKey;
    private final String descriptionKey;
    private final AdvancementFrameType frame;

    /**
     * @param x,y placeholders. The tab is registered with auto-layout, which overwrites both from
     *           the tree shape; they must still be finite and non-negative because this
     *           constructor asserts that before the layout ever runs.
     */
    LocalizedDisplay(@NotNull ItemStack icon, @NotNull String titleKey, @NotNull String descriptionKey,
                     @NotNull AdvancementFrameType frame, boolean showToast, boolean announceChat) {
        super(icon, titleKey, frame, showToast, announceChat, 0f, 0f, descriptionKey);
        this.titleKey = titleKey;
        this.descriptionKey = descriptionKey;
        this.frame = frame;
    }

    public boolean usesComponentDisplay() {
        return true;
    }

    @Override
    @NotNull
    @SuppressWarnings("deprecation")
    public BaseComponent[] getChatTitle() {
        return new BaseComponent[]{colored(titleKey)};
    }

    @Override
    @NotNull
    @SuppressWarnings("deprecation")
    public BaseComponent[] getChatDescription() {
        return new BaseComponent[]{colored(descriptionKey)};
    }

    /** Upstream colours both by frame; an uncoloured component would render white and look wrong. */
    @SuppressWarnings("deprecation")
    private TranslatableComponent colored(String key) {
        TranslatableComponent component = new TranslatableComponent(key);
        component.setColor(frame.getColor());
        return component;
    }
}
