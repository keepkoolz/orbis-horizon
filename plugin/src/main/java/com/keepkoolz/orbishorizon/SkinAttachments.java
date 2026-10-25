package com.keepkoolz.orbishorizon;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.Cosmetic;
import com.hypixel.hytale.protocol.ItemArmor;
import com.hypixel.hytale.protocol.PlayerSkin;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAsset;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAttachment;
import com.hypixel.hytale.server.core.cosmetics.CosmeticRegistry;
import com.hypixel.hytale.server.core.cosmetics.CosmeticsModule;
import com.hypixel.hytale.server.core.cosmetics.PlayerSkinPart;
import com.hypixel.hytale.server.core.cosmetics.PlayerSkinPartTexture;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerSettings;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerSkinComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * T157: rebuilds the player's whole look as model attachments so a camp pack can be added on the back.
 *
 * Adding one ModelAttachment to the player's Model makes the client draw only the attachments (the player would be naked),
 * and resending the skin brings the clothes back but removes the bag. So every piece of the PlayerSkin becomes a
 * ModelAttachment through the cosmetic registry, the body texture and gradient go on the Model, the pieces hidden by the
 * worn armour are skipped, then the bag is added. Removing the bag goes back to the native rendering, never to this
 * conversion without the bag (that lost clothes in the spike).
 *
 * The approach is inspired by the True Backpack mod (CC0, https://github.com/SupremoSama/Hytale-TrueBackpack), rewritten
 * here for the 0.7 jar.
 */
final class SkinAttachments {
    static final String BACK_MODEL = "Items/Back/BackpackBig.blockymodel";

    private static volatile Set<String> skinModels;

    private SkinAttachments() {
    }

    /** True when the model carries our back attachment. */
    static boolean hasBag(Model m) {
        if (m == null || m.getAttachments() == null) {
            return false;
        }
        for (ModelAttachment a : m.getAttachments()) {
            if (BACK_MODEL.equals(a.getModel())) {
                return true;
            }
        }
        return false;
    }

    /**
     * The model rebuilt from the skin: skin pieces as attachments, armour masking applied, {@code extra} added last when not
     * null. Null when the player has no skin or wears a model of another asset (another mod's model is never touched).
     */
    static Model build(Store<EntityStore> store, Ref<EntityStore> player, PlayerSkin skin, ModelAttachment extra) {
        ModelComponent mc = store.getComponent(player, ModelComponent.getComponentType());
        if (mc == null || skin == null) {
            return null;
        }
        Model current = mc.getModel();
        Model base = CosmeticsModule.get().createModel(skin);
        if (base == null || !Objects.equals(base.getModelAssetId(), current.getModelAssetId())) {
            return null;
        }
        CosmeticRegistry registry = CosmeticsModule.get().getRegistry();
        PlayerSettings settings = store.getComponent(player, PlayerSettings.getComponentType());
        Set<Cosmetic> hidden = hiddenByArmor(store, player, settings);
        BodySkin body = bodySkin(skin, base, registry);

        List<ModelAttachment> out = new ArrayList<>();
        // attachments of other sources that are neither skin pieces nor our bag
        Set<String> skinModelIds = skinModels(registry);
        if (current.getAttachments() != null) {
            for (ModelAttachment a : current.getAttachments()) {
                String m = a.getModel();
                if (m != null && !skinModelIds.contains(m) && !m.startsWith("Items/Hats/InnerHair_") && !BACK_MODEL.equals(m)) {
                    out.add(a);
                }
            }
        }
        addSkin(out, skin, hidden, body.gradientId(), registry, new ArrayList<>());
        if (extra != null) {
            out.add(extra);
        }
        return copy(base, out, body);
    }

    /**
     * Back to the native rendering: the model the game builds from the skin, without added attachments, then the skin is sent
     * again. A model of another asset is left alone.
     */
    static void restoreNative(Store<EntityStore> store, Ref<EntityStore> player) {
        ModelComponent mc = store.getComponent(player, ModelComponent.getComponentType());
        PlayerSkinComponent psc = store.getComponent(player, PlayerSkinComponent.getComponentType());
        if (mc == null || psc == null || psc.getPlayerSkin() == null) {
            return;
        }
        Model base = CosmeticsModule.get().createModel(psc.getPlayerSkin());
        if (base == null || !Objects.equals(base.getModelAssetId(), mc.getModel().getModelAssetId())) {
            return;
        }
        store.replaceComponent(player, ModelComponent.getComponentType(), new ModelComponent(base));
        psc.setNetworkOutdated();
    }

    // ------------------------------------------------------------------------------------------------------------
    // skin conversion (rewritten from True Backpack, CC0)
    // ------------------------------------------------------------------------------------------------------------

    private record BodySkin(String gradientId, String gradientSet, String texture) {
    }

    private static BodySkin bodySkin(PlayerSkin skin, Model base, CosmeticRegistry registry) {
        if (skin.bodyCharacteristic == null) {
            return new BodySkin("", base.getGradientSet(), base.getTexture());
        }
        String[] parts = skin.bodyCharacteristic.split("\\.", -1);
        String assetId = part(parts, 0);
        String gradientId = nz(part(parts, 1), "");
        PlayerSkinPart bodyPart = assetId == null ? null : registry.getBodyCharacteristics().get(assetId);
        if (bodyPart == null) {
            return new BodySkin(gradientId, base.getGradientSet(), base.getTexture());
        }
        return new BodySkin(gradientId, bodyPart.getGradientSet(), bodyPart.getGreyscaleTexture());
    }

    private static Set<Cosmetic> hiddenByArmor(Store<EntityStore> store, Ref<EntityStore> ref, PlayerSettings settings) {
        Set<Cosmetic> hidden = EnumSet.noneOf(Cosmetic.class);
        InventoryComponent.Armor armorComponent = store.getComponent(ref, InventoryComponent.Armor.getComponentType());
        if (armorComponent == null) {
            return hidden;
        }
        ItemContainer armor = armorComponent.getInventory();
        for (short slot = 0; slot < armor.getCapacity(); slot++) {
            ItemStack stack = armor.getItemStack(slot);
            if (stack == null || stack.isEmpty() || stack.getItem().getArmor() == null) {
                continue;
            }
            ItemArmor itemArmor = stack.getItem().getArmor().toPacket();
            if (settings != null) {
                boolean hide = switch (itemArmor.armorSlot) {
                    case Head -> settings.hideHelmet();
                    case Chest -> settings.hideCuirass();
                    case Hands -> settings.hideGauntlets();
                    case Legs -> settings.hidePants();
                };
                if (hide) {
                    continue; // the player chose to hide this armor piece, so it hides no cosmetic
                }
            }
            if (itemArmor.cosmeticsToHide != null) {
                for (Cosmetic c : itemArmor.cosmeticsToHide) {
                    if (c != Cosmetic.Ear) {
                        hidden.add(c);
                    }
                }
            }
        }
        return hidden;
    }

    private static int addSkin(List<ModelAttachment> out, PlayerSkin skin, Set<Cosmetic> hidden, String body, CosmeticRegistry r,
                               List<String> missing) {
        int before = out.size();
        add(out, "skinFeature", skin.skinFeature, r.getSkinFeatures(), body, false, missing);
        add(out, "face", skin.face, r.getFaces(), body, true, missing);
        add(out, "mouth", skin.mouth, r.getMouths(), body, true, missing);
        add(out, "ears", skin.ears, r.getEars(), body, true, missing);
        add(out, "eyebrows", skin.eyebrows, r.getEyebrows(), body, false, missing);
        add(out, "eyes", skin.eyes, r.getEyes(), body, false, missing);
        add(out, "underwear", skin.underwear, r.getUnderwear(), body, false, missing);
        addHaircut(out, skin, r, hidden, body, missing);
        addHidden(out, hidden, Cosmetic.FacialHair, "facialHair", skin.facialHair, r.getFacialHairs(), body, missing);
        addHidden(out, hidden, Cosmetic.Cape, "cape", skin.cape, r.getCapes(), body, missing);
        addHidden(out, hidden, Cosmetic.FaceAccessory, "faceAccessory", skin.faceAccessory, r.getFaceAccessories(), body, missing);
        addHidden(out, hidden, Cosmetic.Gloves, "gloves", skin.gloves, r.getGloves(), body, missing);
        addHidden(out, hidden, Cosmetic.HeadAccessory, "headAccessory", skin.headAccessory, r.getHeadAccessories(), body, missing);
        addHidden(out, hidden, Cosmetic.Overpants, "overpants", skin.overpants, r.getOverpants(), body, missing);
        addHidden(out, hidden, Cosmetic.Overtop, "overtop", skin.overtop, r.getOvertops(), body, missing);
        addHidden(out, hidden, Cosmetic.Pants, "pants", skin.pants, r.getPants(), body, missing);
        addHidden(out, hidden, Cosmetic.Shoes, "shoes", skin.shoes, r.getShoes(), body, missing);
        addHidden(out, hidden, Cosmetic.Undertop, "undertop", skin.undertop, r.getUndertops(), body, missing);
        addHidden(out, hidden, Cosmetic.EarAccessory, "earAccessory", skin.earAccessory, r.getEarAccessories(), body, missing);
        return out.size() - before;
    }

    private static void addHidden(List<ModelAttachment> out, Set<Cosmetic> hidden, Cosmetic c, String name, String rawId,
                                  Map<String, PlayerSkinPart> reg, String body, List<String> missing) {
        if (!hidden.contains(c)) {
            add(out, name, rawId, reg, body, false, missing);
        }
    }

    private static void add(List<ModelAttachment> out, String name, String rawId, Map<String, PlayerSkinPart> reg, String body,
                            boolean bodyGradientWhenNoTexture, List<String> missing) {
        if (rawId == null) {
            return;
        }
        String[] parts = rawId.split("\\.", -1);
        String assetId = part(parts, 0);
        PlayerSkinPart p = assetId == null ? null : reg.get(assetId);
        if (p == null) {
            missing.add(name + "=" + rawId);
            return;
        }
        String textureId = part(parts, 1);
        if (textureId == null && bodyGradientWhenNoTexture) {
            textureId = body;
        }
        String variantId = part(parts, 2);
        if (variantId != null && textureId != null && p.getVariants() != null && !p.getVariants().containsKey(variantId)
                && p.getVariants().containsKey(textureId)) {
            String v = textureId;
            textureId = variantId;
            variantId = v;
        }
        out.add(resolve(p, textureId, variantId, body));
    }

    private static void addHaircut(List<ModelAttachment> out, PlayerSkin skin, CosmeticRegistry r, Set<Cosmetic> hidden, String body,
                                   List<String> missing) {
        if (skin.haircut == null) {
            return;
        }
        String[] parts = skin.haircut.split("\\.", -1);
        String assetId = part(parts, 0);
        String textureId = part(parts, 1);
        String variantId = part(parts, 2);
        PlayerSkinPart p = assetId == null ? null : r.getHaircuts().get(assetId);
        if (p == null) {
            missing.add("haircut=" + skin.haircut);
            return;
        }
        if (variantId != null && textureId != null && p.getVariants() != null && !p.getVariants().containsKey(variantId)
                && p.getVariants().containsKey(textureId)) {
            String v = textureId;
            textureId = variantId;
            variantId = v;
        }
        PlayerSkinPart.HeadAccessoryType headType = PlayerSkinPart.HeadAccessoryType.Simple;
        if (skin.headAccessory != null) {
            String ha = skin.headAccessory.split("\\.", -1)[0];
            PlayerSkinPart hp = ha.isEmpty() ? null : r.getHeadAccessories().get(ha);
            if (hp != null && hp.getHeadAccessoryType() != null) {
                headType = hp.getHeadAccessoryType();
            }
        }
        if (headType == PlayerSkinPart.HeadAccessoryType.FullyCovering && !hidden.contains(Cosmetic.HeadAccessory)) {
            return; // the head accessory covers the whole head, no hair
        }
        String id = helmetHaircutId(assetId, hidden.contains(Cosmetic.Haircut), p.doesRequireGenericHaircut(),
                p.getHairType() != null ? p.getHairType().name() : null);
        PlayerSkinPart generic = id.equals(assetId) ? null : r.getHaircuts().get(id);
        if (generic != null) {
            // the game's swap under a helmet: the generic haircut of the same length, same colour, no variant
            out.add(resolve(generic, textureId, null, body));
            return;
        }
        out.add(resolve(p, textureId, variantId, body));
    }

    /**
     * The haircut drawn under armour that hides it: the generic haircut of the same length (Cosmetics/CharacterCreator/
     * HaircutFallbacks.json maps Short, Medium and Long to GenericShort, GenericMedium and GenericLong) when the haircut
     * requires it, the haircut itself otherwise. The InnerHair models of True Backpack do not exist in the 0.7 assets.
     */
    static String helmetHaircutId(String ownId, boolean hidden, boolean requiresGeneric, String hairType) {
        return hidden && requiresGeneric && hairType != null ? "Generic" + hairType : ownId;
    }

    private static ModelAttachment resolve(PlayerSkinPart part, String textureId, String variantId, String bodyGradient) {
        String gradient = nz(textureId, bodyGradient);
        Map<String, PlayerSkinPart.Variant> variants = part.getVariants();
        if (variants != null && !variants.isEmpty()) {
            PlayerSkinPart.Variant v = variantId == null ? null : variants.get(variantId);
            if (v == null) {
                return att(part.getModel(), part.getGreyscaleTexture(), part.getGradientSet(), gradient);
            }
            Map<String, PlayerSkinPartTexture> vt = v.getTextures();
            if (vt != null && !vt.isEmpty() && textureId != null) {
                PlayerSkinPartTexture t = vt.get(textureId);
                if (t != null) {
                    return t.getBaseColor() == null ? att(v.getModel(), t.getTexture(), part.getGradientSet(), gradient)
                            : att(v.getModel(), t.getTexture(), null, null);
                }
            }
            return att(v.getModel() != null ? v.getModel() : part.getModel(),
                    v.getGreyscaleTexture() != null ? v.getGreyscaleTexture() : part.getGreyscaleTexture(), part.getGradientSet(), gradient);
        }
        Map<String, PlayerSkinPartTexture> pt = part.getTextures();
        if (pt != null && !pt.isEmpty() && textureId != null) {
            PlayerSkinPartTexture t = pt.get(textureId);
            if (t != null) {
                return t.getBaseColor() == null ? att(part.getModel(), t.getTexture(), part.getGradientSet(), gradient)
                        : att(part.getModel(), t.getTexture(), null, null);
            }
        }
        return att(part.getModel(), part.getGreyscaleTexture(), part.getGradientSet(), gradient);
    }

    private static ModelAttachment att(String model, String texture, String gradientSet, String gradientId) {
        return new ModelAttachment(model, texture, empty(gradientSet), empty(gradientId), 1.0);
    }

    private static Set<String> skinModels(CosmeticRegistry r) {
        Set<String> s = skinModels;
        if (s == null) {
            Set<String> m = new HashSet<>(128);
            for (Map<String, PlayerSkinPart> reg : List.of(r.getSkinFeatures(), r.getFaces(), r.getMouths(), r.getEars(), r.getEyebrows(),
                    r.getEyes(), r.getUnderwear(), r.getHaircuts(), r.getFacialHairs(), r.getCapes(), r.getFaceAccessories(), r.getGloves(),
                    r.getHeadAccessories(), r.getOverpants(), r.getOvertops(), r.getPants(), r.getShoes(), r.getUndertops(),
                    r.getEarAccessories())) {
                for (PlayerSkinPart p : reg.values()) {
                    if (p.getModel() != null) {
                        m.add(p.getModel());
                    }
                    if (p.getVariants() != null) {
                        for (PlayerSkinPart.Variant v : p.getVariants().values()) {
                            if (v.getModel() != null) {
                                m.add(v.getModel());
                            }
                        }
                    }
                }
            }
            s = Collections.unmodifiableSet(m);
            skinModels = s;
        }
        return s;
    }

    /** Copy of the model with the given attachments and the body texture and gradient (every other field through its getter). */
    private static Model copy(Model base, List<ModelAttachment> attachments, BodySkin body) {
        String gradientSet = body.gradientSet() != null ? body.gradientSet() : base.getGradientSet();
        String gradientId = !body.gradientId().isEmpty() ? body.gradientId() : base.getGradientId();
        String texture = body.texture() != null ? body.texture() : base.getTexture();
        Map<String, ModelAsset.AnimationSet> anims = new LinkedHashMap<>(base.getAnimationSetMap());
        return new Model(base.getModelAssetId(), base.getScale(), base.getRandomAttachmentIds(), attachments.toArray(new ModelAttachment[0]),
                base.getBoundingBox(), base.getModel(), texture, gradientSet, gradientId, base.getEyeHeight(), base.getCrouchOffset(),
                base.getSittingOffset(), base.getSleepingOffset(), anims, base.getCamera(), base.getLight(), base.getParticles(),
                base.getTrails(), base.getPhysicsValues(), base.getDetailBoxes(), base.getPhobia(), base.getPhobiaModelAssetId());
    }

    /** ModelAttachment has no equals(), so the comparison goes field by field. */
    static boolean sameState(Model a, Model b) {
        ModelAttachment[] x = a.getAttachments() == null ? new ModelAttachment[0] : a.getAttachments();
        ModelAttachment[] y = b.getAttachments() == null ? new ModelAttachment[0] : b.getAttachments();
        if (x.length != y.length || !Objects.equals(a.getTexture(), b.getTexture())
                || !Objects.equals(a.getGradientSet(), b.getGradientSet()) || !Objects.equals(a.getGradientId(), b.getGradientId())) {
            return false;
        }
        for (int i = 0; i < x.length; i++) {
            if (!Objects.equals(x[i].getModel(), y[i].getModel()) || !Objects.equals(x[i].getTexture(), y[i].getTexture())
                    || !Objects.equals(x[i].getGradientSet(), y[i].getGradientSet())
                    || !Objects.equals(x[i].getGradientId(), y[i].getGradientId()) || Double.compare(x[i].getWeight(), y[i].getWeight()) != 0) {
                return false;
            }
        }
        return true;
    }

    private static String part(String[] parts, int i) {
        return i < parts.length && parts[i] != null && !parts[i].isEmpty() ? parts[i] : null;
    }

    private static String nz(String v, String fallback) {
        return v == null || v.isEmpty() ? fallback : v;
    }

    private static String empty(String v) {
        return v == null || v.isEmpty() ? null : v;
    }

}
