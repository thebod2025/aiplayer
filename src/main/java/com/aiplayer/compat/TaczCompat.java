package com.aiplayer.compat;

import com.aiplayer.AiPlayerMod;
import com.aiplayer.config.AiPlayerConfig;
import com.aiplayer.entity.AiPlayer;
import com.aiplayer.util.InventoryLock;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * TaCZ（Timeless &amp; Classics Guns: Zero Refabricated）兼容。
 * <p>
 * 不做硬依赖，全部走反射：没装 tacz 时 {@link #loaded()} 为 false，这里所有方法都安全地返回空值。
 * <p>
 * 服务端假玩家没有客户端发来的按键包，原版的「右键开枪」根本不会进到 TaCZ 的射击逻辑，
 * 所以这里直接调 TaCZ 的服务端接口 {@code IGunOperator}：
 * <ul>
 *   <li>{@code draw()} 拔枪（切枪后必须先拔枪，否则 shoot 返回 NOT_DRAW）</li>
 *   <li>{@code shoot()} 开枪（返回 SUCCESS / COOL_DOWN / NO_AMMO / IS_RELOADING ...）</li>
 *   <li>{@code reload()} 换弹（弹匣打空或没子弹时触发）</li>
 *   <li>{@code aim(true/false)} 开镜，降低散布</li>
 * </ul>
 * 选枪时按「有弹匣 → 有备弹 → DPS（伤害 × 射速）」打分，挑手感最好的一把，并把非快捷栏的枪换到手上。
 */
public class TaczCompat implements ModCompatibility {
    public static final TaczCompat INSTANCE = new TaczCompat();

    /** 贴脸距离：小于这个距离就换成近战武器，不要拿枪怼人 */
    public static final double MELEE_SWITCH_DISTANCE = 2.5D;
    /** 超过这个距离才值得开枪 */
    public static final double MIN_GUN_DISTANCE = 3.0D;
    /** 枪的可用射程（格）：再远子弹散布太大，交给追击 */
    public static final double GUN_RANGE = 48.0D;



    private static final boolean LOADED;
    private static Method mIGunOrNull;
    private static Method mFromLiving;
    private static Method mCurrentAmmo;
    private static Method mHasInvAmmo;
    private static Method mRpm;
    private static Method mGunId;
    private static Method mDraw;
    private static Method mShoot;
    /** 带客户端时间戳的 shoot 重载：假玩家必须走这个，否则 TaCZ 的反作弊校验会一直 NETWORK_FAIL */
    private static Method mShootTs;
    /** ShooterDataHolder.baseTimestamp：假玩家恒为 0，ts 要按它反算 */
    private static Method mDataHolder;
    private static java.lang.reflect.Field fBaseTimestamp;
    private static Method mReload;
    private static Method mAim;
    private static Method mDrawCd;
    private static Method mReloadState;
    private static Method mStateType;
    private static Method mIsReloading;
    private static Method mShootCd;
    private static Method mBolt;
    private static Method mGunIndex;
    private static Method mBulletData;
    private static Method mDamage;
    /** 枪的口径（GunData.ammoId）与弹药物品的 IAmmo 判定，用于诊断口径不匹配 */
    private static Method mGunDataOfIndex;
    private static Method mAmmoIdOfGun;
    private static Method mIAmmoOrNull;
    private static Method mAmmoIdOfStack;
    private static Method mIsAmmoOfGun;
    /** 配枪用：枚举枪包里所有枪的 id、把 id 写进物品、读弹匣容量 */
    private static Method mAllGuns;
    private static Method mSetGunId;
    private static Method mSetAmmoCount;
    private static Method mSetAmmoId;
    private static Method mAmmoAmount;

    /** 枪械满伤缓存（同一把枪只查一次资源索引），key 是枪的注册 id */
    private static final Map<String, Float> DAMAGE_CACHE = new HashMap<>();

    static {
        boolean ok = false;
        try {
            if (FabricLoader.getInstance().isModLoaded("tacz")) {
                Class<?> cIGun = Class.forName("com.tacz.guns.api.item.IGun");
                Class<?> cOp = Class.forName("com.tacz.guns.api.entity.IGunOperator");

                // 核心能力（缺了就等于不支持，直接降级）
                mIGunOrNull = cIGun.getMethod("getIGunOrNull", ItemStack.class);
                mCurrentAmmo = cIGun.getMethod("getCurrentAmmoCount", ItemStack.class);
                mHasInvAmmo = cIGun.getMethod("hasInventoryAmmo", LivingEntity.class, ItemStack.class, boolean.class);
                mFromLiving = cOp.getMethod("fromLivingEntity", LivingEntity.class);
                mDraw = cOp.getMethod("draw", Supplier.class);
                mShoot = cOp.getMethod("shoot", Supplier.class, Supplier.class);
                mReload = cOp.getMethod("reload");
                mShootTs = optional(cOp, "shoot", Supplier.class, Supplier.class, long.class);
                mDataHolder = optional(cOp, "getDataHolder");
                if (mDataHolder != null) {
                    try {
                        fBaseTimestamp = Class.forName("com.tacz.guns.entity.shooter.ShooterDataHolder")
                                .getField("baseTimestamp");
                    } catch (Throwable ignored) {
                        fBaseTimestamp = null;
                    }
                }

                // 锦上添花的能力：版本差异时单独降级，不影响开枪
                mRpm = optional(cIGun, "getRPM", ItemStack.class);
                mGunId = optional(cIGun, "getGunId", ItemStack.class);
                mAim = optional(cOp, "aim", boolean.class);
                mDrawCd = optional(cOp, "getSynDrawCoolDown");
                mReloadState = optional(cOp, "getSynReloadState");
                mShootCd = optional(cOp, "getSynShootCoolDown");
                mBolt = optional(cOp, "bolt");
                mStateType = optional(Class.forName("com.tacz.guns.api.entity.ReloadState"), "getStateType");
                mIsReloading = optional(
                        Class.forName("com.tacz.guns.api.entity.ReloadState$StateType"), "isReloading");
                mGunIndex = optional(Class.forName("com.tacz.guns.api.TimelessAPI"),
                        "getCommonGunIndex", Identifier.class);
                mBulletData = optional(Class.forName("com.tacz.guns.resource.index.CommonGunIndex"), "getBulletData");
                mDamage = optional(Class.forName("com.tacz.guns.resource.pojo.data.gun.BulletData"),
                        "getDamageAmount");
                // 弹药口径：诊断"背包有子弹却备弹=false"用（枪和弹药的口径对不上时就是这个现象）
                mGunDataOfIndex = optional(Class.forName("com.tacz.guns.resource.index.CommonGunIndex"), "getGunData");
                mAmmoIdOfGun = optional(Class.forName("com.tacz.guns.resource.pojo.data.gun.GunData"), "getAmmoId");
                Class<?> cAmmo = Class.forName("com.tacz.guns.api.item.IAmmo");
                mIAmmoOrNull = optional(cAmmo, "getIAmmoOrNull", ItemStack.class);
                mAmmoIdOfStack = optional(cAmmo, "getAmmoId", ItemStack.class);
                mIsAmmoOfGun = optional(cAmmo, "isAmmoOfGun", ItemStack.class, ItemStack.class);
                // 给 AI 配枪用（背包里没有枪时补一把）：枪包枚举 + 往物品里写枪 id / 弹匣 / 口径
                mAllGuns = optional(Class.forName("com.tacz.guns.api.TimelessAPI"), "getAllCommonGunIndex");
                mSetGunId = optional(cIGun, "setGunId", ItemStack.class, Identifier.class);
                mSetAmmoCount = optional(cIGun, "setCurrentAmmoCount", ItemStack.class, int.class);
                mSetAmmoId = optional(cAmmo, "setAmmoId", ItemStack.class, Identifier.class);
                mAmmoAmount = optional(Class.forName("com.tacz.guns.resource.pojo.data.gun.GunData"),
                        "getAmmoAmount");
                ok = true;
            }
        } catch (Throwable t) {
            // 装了 tacz 但核心 API 对不上（版本换了包名 / 方法签名）：降级成"没有兼容"，不能拦住游戏启动
            AiPlayerMod.LOGGER.warn("[aiplayer] tacz 兼容初始化失败，已跳过: {}", t.toString());
            ok = false;
        }
        LOADED = ok;
    }

    /** 反射查方法：找不到返回 null（调用处都有 null 判断），不让单个方法缺失拖垮整个兼容 */
    private static Method optional(Class<?> owner, String name, Class<?>... params) {
        try {
            return owner.getMethod(name, params);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static boolean loaded() {
        return LOADED;
    }

    @Override
    public String modId() {
        return "tacz";
    }

    @Override
    public boolean isModItem(ItemStack item) {
        if (item.isEmpty()) {
            return false;
        }
        if (LOADED) {
            return gunOf(item) != null;
        }
        // 没装 tacz 时的兜底判断（给"背包里有枪"这类通用逻辑用，不会真的去开枪）
        String id = item.getItem().getDescriptionId();
        return id.contains("tacz");
    }

    /** 是不是枪（而不是弹药 / 配件） */
    public boolean isGun(ItemStack item) {
        if (item.isEmpty()) {
            return false;
        }
        if (LOADED) {
            return gunOf(item) != null;
        }
        String name = item.getItem().getClass().getSimpleName();
        if (name.contains("Ammo") || name.contains("Magazine") || name.contains("Attachment")) {
            return false;
        }
        String id = item.getItem().getDescriptionId();
        return id.contains("tacz") && (name.contains("Gun") || name.contains("ItemGun")
                || name.contains("ModernKineticGun") || extraLooksLikeGun(item));
    }

    private boolean extraLooksLikeGun(ItemStack item) {
        for (Class<?> c = item.getItem().getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            String n = c.getSimpleName();
            if (n.contains("Gun") || n.contains("Firearm")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 是不是 TaCZ 的物品（枪 / 弹药 / 弹匣 / 配件）。
     * <p>
     * 和 {@link #isGunItem} 的区别：枪的判定要反查 {@code IGun}，而弹药、弹匣、配件不是枪，
     * 却同样是 TaCZ 注册的物品。清理背包时按"同一种 Item"判重会把不同口径的弹匣当成重复品丢掉
     * —— AI 因此把子弹全扔了，之后枪里没弹也就再也打不响。
     */
    public static boolean isTaczItem(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        return com.aiplayer.util.ItemIdUtil.idOf(stack.getItem()).startsWith("tacz:");
    }

    @Override
    public void useModItem(AiPlayer player, ItemStack item) {
        // 开枪统一走 shoot()（见下），这里不再依赖原版右键
    }

    @Override
    public boolean canInteractWith(BlockState block) {
        return true;
    }

    @Override
    public void interactWithBlock(AiPlayer player, BlockPos pos) {
        // 不需要特殊处理
    }

    // ------------------------------------------------------------------
    // 给 RangedController 用的能力
    // ------------------------------------------------------------------

    /** 背包里有没有枪（含快捷栏） */
    public static boolean hasGun(AiPlayer player) {
        if (!LOADED) {
            return false;
        }
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (isGunStatic(inv.getItem(i))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 背包里的枪械明细：{@code [槽位]枪id 弹匣=N 备弹=true/false}，排查"没有可用的枪"时直接看这里。
     * <p>
     * 分成两段：一段是**识别到的枪**（能反查到 IGun），一段是**tacz 命名空间的其它物品**。
     * 前者为空但后者不为空，说明物品给到了、但反查 IGun 失败（版本 API 对不上）；
     * 两者都为空，说明这把枪根本没进到这个 AI 的背包里。
     */
    public static String gunSummary(AiPlayer player) {
        if (!LOADED) {
            return "tacz 兼容层未加载";
        }
        Inventory inv = player.getInventory();
        StringBuilder guns = new StringBuilder();
        StringBuilder others = new StringBuilder();
        int gunCount = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            if (!isGunStatic(stack)) {
                if (isTaczItem(stack)) {
                    others.append(others.length() == 0 ? "" : "，")
                            .append(i).append(":").append(com.aiplayer.util.ItemIdUtil.idOf(stack.getItem()))
                            .append("x").append(stack.getCount());
                }
                continue;
            }
            gunCount++;
            Object gun = gunOf(stack);
            guns.append(guns.length() == 0 ? "" : "，").append(i).append(":")
                    .append(gunIdOf(gun, stack))
                    .append(" 弹匣=").append(gun == null ? "?" : ammoOf(gun, stack))
                    .append(" 备弹=").append(gun != null && reserveAmmoOf(gun, player, stack))
                    .append(" 需弹药=").append(gun == null ? "?" : requiredAmmoId(gun, stack))
                    .append(" 匹配弹药=").append(gun == null ? 0 : countMatchingAmmo(player, stack));
        }
        StringBuilder sb = new StringBuilder("枪数=").append(gunCount);
        if (guns.length() > 0) {
            sb.append(" | ").append(guns);
        }
        if (others.length() > 0) {
            sb.append(" | 其它tacz=").append(others);
        }
        return sb.toString();
    }

    /**
     * 挑一把"最好的枪"，返回它所在的背包槽位。
     * <p>
     * 打分：弹匣里还有子弹（能立刻开火）优先，其次背包里有对应口径的备弹（能换弹），
     * 最后比 DPS（伤害 × 射速）。空枪又没备弹的排最后，但**仍然返回** ——
     * 以前直接返回 -1，表现就是"给 AI 发了枪和子弹却连枪都不掏"，而且没法诊断；
     * 现在至少会把枪拿到手上（打不响时 tickGun 会换弹 / 报 NO_AMMO）。
     *
     * @return 槽位号（0~40），背包里一把枪都没有才返回 -1
     */
    public static int bestGunSlot(AiPlayer player) {
        if (!LOADED) {
            return -1;
        }
        Inventory inv = player.getInventory();
        int bestSlot = -1;
        double bestScore = -1.0D;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!isGunStatic(stack)) {
                continue;
            }
            Object gun = gunOf(stack);
            int mag = gun == null ? 0 : ammoOf(gun, stack);
            boolean reserve = gun != null && reserveAmmoOf(gun, player, stack);
            double score = (mag > 0 ? 1000.0D : 0.0D) + (reserve ? 200.0D : 0.0D)
                    + (gun == null ? 0.0D : dpsOf(gun, stack));
            // 快捷栏里的枪同等条件下优先（省一次背包交换，切枪更快）
            if (i < Inventory.SELECTION_SIZE) {
                score += 5.0D;
            }
            if (score > bestScore) {
                bestScore = score;
                bestSlot = i;
            }
        }
        return bestSlot;
    }

    /**
     * 给 AI 补一把枪 + 配套弹药（背包里已经有枪就什么都不做）。
     * <p>
     * 为什么需要这一步：TaCZ 的所有枪共用同一个物品 id（{@code tacz:modern_kinetic_gun}），
     * "到底是哪把枪"只存在物品数据里。玩家在装备包界面里手动塞进去的枪、或者从创造栏拖的枪，
     * 很容易变成一把<b>没有枪种数据</b>的空壳 —— 看着是枪，TaCZ 反查枪种失败，
     * 于是打不响，表现就是"给 AI 发了枪却一次都不开枪"。
     * <p>
     * 这里反过来做：从已安装的枪包里枚举一把真实存在的枪 id，写进物品、顺带装满弹匣，
     * 再配同口径的弹药。这样 AI 手上的枪一定是"能开火"的。
     *
     * @return 给人类看的说明（命令直接回显）
     */
    public static String grantStarterGun(AiPlayer player) {
        if (!LOADED) {
            return "tacz 兼容层未加载";
        }
        if (mAllGuns == null || mSetGunId == null) {
            return "当前 tacz 版本缺少配枪接口";
        }
        Identifier picked = pickGunId();
        if (picked == null) {
            return "枪包里没有任何枪械数据（检查 mods 里是否装了枪包）";
        }
        Item ammoItem = BuiltInRegistries.ITEM.getValue(Identifier.parse("tacz:ammo"));
        if (ammoItem == null) {
            return "找不到 tacz 的弹药物品";
        }
        try {
            // 一、先修好背包里已有的枪：空壳（没有枪种数据）就地补上枪种 + 弹匣，
            //     而不是"有枪就直接返回" —— 之前那样正好放过了打不响的那把。
            Inventory inv = player.getInventory();
            int fixed = 0;
            int magSize = 0;
            String ammoIdText = null;
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack stack = inv.getItem(i);
                if (stack.isEmpty() || !isGunItem(stack)) {
                    continue;
                }
                Object gun = gunOf(stack);
                if (gun == null) {
                    continue;
                }
                String existing = gunIdOf(gun, stack);
                if (existing == null) {
                    String[] info = stampGun(gun, stack, picked);
                    ammoIdText = info[0];
                    magSize = parseIntOr0(info[1]);
                    fixed++;
                } else {
                    // 有枪种但弹匣是空的：顺手装满，省掉 AI 换弹那两秒
                    Object mag = currentAmmoOf(gun, stack);
                    if (mag instanceof Number n && n.intValue() <= 0) {
                        String[] info = stampGun(gun, stack, Identifier.tryParse(existing));
                        magSize = parseIntOr0(info[1]);
                    }
                }
            }
            // 二、背包里确实一把枪都没有：造一把带枪种数据的
            if (fixed == 0 && !hasGun(player)) {
                Item gunItem = BuiltInRegistries.ITEM.getValue(Identifier.parse("tacz:modern_kinetic_gun"));
                if (gunItem == null) {
                    return "找不到 tacz 的枪物品";
                }
                ItemStack gunStack = new ItemStack(gunItem);
                Object gun = gunOf(gunStack);
                if (gun == null) {
                    return "这个物品反查不到 IGun（tacz 版本差异）";
                }
                String[] info = stampGun(gun, gunStack, picked);
                ammoIdText = info[0];
                magSize = info[1] == null ? 0 : Integer.parseInt(info[1]);
                inv.add(gunStack);
            }
            // 三、配同口径弹药：以"AI 实际会用的那把枪"为准，而不是 pickGunId() 挑的那把。
            //     这里曾经踩过坑：套装随机给了 AI 一把 aa12（口径 tacz:12g），而 pickGunId() 返回的是
            //     另一把枪，于是补进来的是**那把枪**的口径。手上这把依然 0 备弹 ——
            //     弹匣里 1 发打完就彻底哑火，表现就是"AI 永远打不到人"。
            ItemStack used = gunInUse(player);
            String needAmmo = ammoIdNeededBy(used);
            if (needAmmo == null) {
                needAmmo = ammoIdText != null ? ammoIdText : ammoIdOfGunId(picked);
            }
            if (used != null && needAmmo != null && mSetAmmoId != null) {
                Object usedGun = gunOf(used);
                // 弹匣是空的就顺手装满（省掉一整轮换弹）；枪种本身缺失的也在这里补
                if (usedGun != null && currentAmmoOf(usedGun, used) instanceof Number n && n.intValue() <= 0) {
                    String gid = gunIdOf(usedGun, used);
                    String[] info = stampGun(usedGun, used, gid == null ? picked : Identifier.tryParse(gid));
                    magSize = parseIntOr0(info[1]);
                }
                // 备弹为 0 才补：口径对不上时 hasInventoryAmmo 一定是 false，正好能把这种情况兜住
                if (countMatchingAmmo(player, used) <= 0) {
                    Identifier ammoId = Identifier.tryParse(needAmmo);
                    if (ammoId != null) {
                        for (int i = 0; i < 2; i++) {
                            ItemStack ammo = new ItemStack(ammoItem, 64);
                            Object iammo = mIAmmoOrNull == null ? null : mIAmmoOrNull.invoke(null, ammo);
                            if (iammo == null) {
                                break;
                            }
                            mSetAmmoId.invoke(iammo, ammo, ammoId);
                            inv.add(ammo);
                        }
                    }
                }
            }
            String after = gunSummary(player);
            AiPlayerMod.LOGGER.info("[aiplayer] 配枪完成（修复空壳 {} 把，枪种 {}）：{}",
                    fixed, picked, after);
            return (fixed > 0 ? "已修好 " + fixed + " 把空壳枪" : "已配枪")
                    + " " + picked + "（弹匣 " + magSize + "）｜" + after;
        } catch (Throwable t) {
            AiPlayerMod.LOGGER.warn("[aiplayer] 配枪失败", t);
            return "配枪失败：" + t;
        }
    }

    /** AI 实际会用的那把枪：评分最高的枪槽，没有槽就退回手上那把 */
    private static ItemStack gunInUse(AiPlayer player) {
        int slot = bestGunSlot(player);
        ItemStack s = slot >= 0 ? player.getInventory().getItem(slot) : player.getMainHandItem();
        if (s == null || s.isEmpty()) {
            s = player.getMainHandItem();
        }
        return s;
    }

    /** 某把枪物品需要的口径 id（不是枪 / 读不到数据 → null） */
    private static String ammoIdNeededBy(ItemStack gunStack) {
        if (gunStack == null || gunStack.isEmpty() || !isGunItem(gunStack)) {
            return null;
        }
        Object gun = gunOf(gunStack);
        String gid = gun == null ? null : gunIdOf(gun, gunStack);
        Identifier id = gid == null ? null : Identifier.tryParse(gid);
        return id == null ? null : ammoIdOfGunId(id);
    }

    /** 读当前弹匣数（拿不到返回 null） */
    private static Object currentAmmoOf(Object gun, ItemStack stack) {
        if (mCurrentAmmo == null) {
            return null;
        }
        try {
            return mCurrentAmmo.invoke(gun, stack);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 把 {@code "5"} 这类字符串安全转成 int，失败给 0 */
    private static int parseIntOr0(String s) {
        if (s == null) {
            return 0;
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    /**
     * 往一把枪物品里写入枪种 + 装满弹匣。
     *
     * @return {@code [0]} = 需要的弹药 id（可能是 null），{@code [1]} = 弹匣容量字符串（可能是 null）
     */
    private static String[] stampGun(Object gun, ItemStack stack, Identifier gunId) {
        String[] out = new String[]{null, null};
        if (gunId == null) {
            return out;
        }
        try {
            mSetGunId.invoke(gun, stack, gunId);
        } catch (Throwable ignored) {
            return out;
        }
        int magSize = 0;
        try {
            Object opt = mGunIndex == null ? null : mGunIndex.invoke(null, gunId);
            if (opt instanceof Optional<?> o && o.isPresent() && mGunDataOfIndex != null) {
                Object data = mGunDataOfIndex.invoke(o.get());
                if (data != null && mAmmoIdOfGun != null) {
                    Object ammoId = mAmmoIdOfGun.invoke(data);
                    out[0] = ammoId == null ? null : ammoId.toString();
                }
                if (data != null && mAmmoAmount != null) {
                    Object n = mAmmoAmount.invoke(data);
                    magSize = n instanceof Number num ? num.intValue() : 0;
                }
            }
        } catch (Throwable ignored) {
            // 资源索引缺失时不填弹匣，交给 AI 自己换弹
        }
        if (magSize > 0) {
            try {
                if (mSetAmmoCount != null) {
                    mSetAmmoCount.invoke(gun, stack, magSize);
                }
                out[1] = Integer.toString(magSize);
            } catch (Throwable ignored) {
                // 弹匣字段名在这个版本不同，忽略
            }
        }
        return out;
    }

    /** 从枪包里挑一把有对应口径弹药的枪（按 id 排序，保证每次同一把） */
    private static Identifier pickGunId() {
        try {
            Object set = mAllGuns.invoke(null);
            if (!(set instanceof Set<?> entries) || entries.isEmpty()) {
                return null;
            }
            List<Identifier> ids = new ArrayList<>();
            for (Object e : entries) {
                if (e instanceof Map.Entry<?, ?> entry && entry.getKey() instanceof Identifier id) {
                    ids.add(id);
                }
            }
            ids.sort((a, b) -> a.toString().compareTo(b.toString()));
            for (Identifier id : ids) {
                if (ammoIdOfGunId(id) != null) {
                    return id;
                }
            }
            return ids.isEmpty() ? null : ids.get(0);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 查某个枪 id 需要的口径（拿不到就返回 null，说明这把枪没装弹药数据，跳过） */
    private static String ammoIdOfGunId(Identifier gunId) {
        try {
            if (mGunIndex == null || mGunDataOfIndex == null || mAmmoIdOfGun == null) {
                return null;
            }
            Object opt = mGunIndex.invoke(null, gunId);
            if (!(opt instanceof Optional<?> o) || o.isEmpty()) {
                return null;
            }
            Object data = mGunDataOfIndex.invoke(o.get());
            Object ammo = data == null ? null : mAmmoIdOfGun.invoke(data);
            return ammo == null ? null : ammo.toString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 手动试射一次（{@code /aiplayer gun <名字> test}）：跳过"有没有敌人"直接扣扳机，
     * 把 TaCZ 的原始结果打印出来。排查"不开枪"时这一步能直接定位是拔枪、子弹、冷却还是反射调用的问题。
     */
    public static String testShoot(AiPlayer player) {
        if (!LOADED) {
            return "tacz 兼容层未加载（没装 tacz 或 API 签名对不上）";
        }
        String summary = gunSummary(player);
        int slot = bestGunSlot(player);
        if (slot < 0) {
            // 一把枪都没识别到：把 tacz 物品原样列出来，一眼能分清是"没给到"还是"API 反查失败"
            return "没有识别到枪（" + summary + "）";
        }
        // 诊断命令强制切枪：正常战斗里抢不过进食 / 逃跑时槽位不会切，但试射必须能拿到枪
        if (!selectGun(player, slot, true)) {
            return "切枪失败（槽位越界），" + summary;
        }
        String before = "弹匣=" + loadedAmmo(player) + " 备弹=" + hasReserveAmmo(player) + "｜" + summary;
        draw(player);
        if (drawing(player)) {
            return "拔枪中（draw 冷却还没走完），" + before;
        }
        // 弹匣空但背包里有弹：先换一次弹（换弹要几秒，这里只报告"已开始"，让玩家再执行一次 test）
        if (loadedAmmo(player) <= 0) {
            if (hasReserveAmmo(player)) {
                reload(player);
                return "已开始换弹（弹匣=0），等 2 秒再执行一次 test｜" + summary;
            }
            return "枪里没弹，背包也没有同口径备弹｜" + summary;
        }
        String result = shootResult(player);
        return (result == null ? "反射调用失败" : result) + "，" + before;
    }

    /**
     * 枪械诊断（{@code /aiplayer gun <名字>}）：把"为什么不开枪"需要的所有状态打出来。
     * <p>
     * 排查时看这一行就够：没装/反射失败 → 配置没开远程 → 背包没枪 → 没子弹 → 没敌人。
     */
    public static String debug(AiPlayer player) {
        StringBuilder sb = new StringBuilder("tacz=").append(LOADED ? "ok" : "未加载");
        if (!LOADED) {
            return sb.append("（没装 tacz，或 API 签名对不上，见启动日志）").toString();
        }
        // 带时间戳的 shoot 重载有没有绑上：没绑上就只能走 2 参版本，
        // TaCZ 的反作弊校验会一直返回 NETWORK_FAIL（一枪都不打）
        sb.append(" | 时间戳重载=").append(mShootTs != null ? "ok" : "缺");
        sb.append(" | 有枪=").append(hasGun(player));
        int slot = bestGunSlot(player);
        sb.append(" | 可用枪槽=").append(slot);
        ItemStack held = player.getMainHandItem();
        boolean heldGun = isGunItem(held);
        sb.append(" | 手上=").append(held.getHoverName().getString())
                .append(heldGun ? "(枪)" : "(非枪)");
        if (heldGun) {
            Object gun = gunOf(held);
            sb.append(" 弹匣=").append(gun == null ? "?" : ammoOf(gun, held))
                    .append(" 备弹=").append(gun != null && reserveAmmoOf(gun, player, held));
        }
        sb.append(" | ").append(gunSummary(player));
        sb.append(" | 远程开关=").append(player.profile().ranged)
                .append(" 战斗开关=").append(player.profile().combat)
                .append(" | 强开PvP=").append(player.profile().alwaysPvp);
        AiPlayerConfig cfg = AiPlayerConfig.get();
        sb.append(" | 混战=").append(cfg.aiDuel)
                .append(" 参战队伍=").append(cfg.aiWarTeams)
                .append(" 本人队伍=").append(player.profile().team)
                .append(" 参战=").append(cfg.teamAtWar(player.profile().team));
        if (player.brain() != null) {
            Object target = player.brain().currentTarget();
            sb.append(" | 当前目标=").append(target == null ? "无" : target.toString())
                    .append(" | 上次射击=").append(player.brain().lastGunResult());
        }
        return sb.toString();
    }

    /**
     * 把枪拿到手上：不在快捷栏就先跟当前选中格交换，然后切过去。
     *
     * @return true 表示手上已经是这把枪
     */
    public static boolean selectGun(AiPlayer player, int slot) {
        return selectGun(player, slot, false);
    }

    /**
     * @param force 诊断用：跳过快捷栏锁直接切（正常战斗必须走锁，否则会和进食 / 逃跑抢槽位）
     */
    private static boolean selectGun(AiPlayer player, int slot, boolean force) {
        Inventory inv = player.getInventory();
        if (slot < 0 || slot >= inv.getContainerSize()) {
            return false;
        }
        int hotbar = slot;
        if (slot >= Inventory.SELECTION_SIZE) {
            hotbar = freeHotbarSlot(inv);
            ItemStack gun = inv.getItem(slot);
            ItemStack old = inv.getItem(hotbar);
            inv.setItem(hotbar, gun);
            inv.setItem(slot, old);
        }
        if (force) {
            player.getInventory().setSelectedSlot(hotbar);
            return true;
        }
        return player.selectSlot("ranged", InventoryLock.PRIO_RANGED, hotbar);
    }

    private static int freeHotbarSlot(Inventory inv) {
        for (int i = 0; i < Inventory.SELECTION_SIZE; i++) {
            if (inv.getItem(i).isEmpty()) {
                return i;
            }
        }
        return inv.getSelectedSlot();
    }

    /** 拔枪（切枪后调用；TaCZ 的 shoot 在拔枪完成前会返回 NOT_DRAW） */
    public static void draw(AiPlayer player) {
        Object op = operator(player);
        if (op == null || mDraw == null) {
            return;
        }
        try {
            mDraw.invoke(op, (Supplier<ItemStack>) player::getMainHandItem);
        } catch (Throwable ignored) {
            // 拔枪失败：下一 tick 还会再试
        }
    }

    /** 开镜 */
    public static void aim(AiPlayer player, boolean aiming) {
        Object op = operator(player);
        if (op == null || mAim == null) {
            return;
        }
        try {
            mAim.invoke(op, aiming);
        } catch (Throwable ignored) {
            // 开镜只是减少散布，失败不影响射击
        }
    }

    /** 换弹（弹匣打空 / 手动换弹时调用） */
    public static void reload(AiPlayer player) {
        Object op = operator(player);
        if (op == null || mReload == null) {
            return;
        }
        try {
            mReload.invoke(op);
        } catch (Throwable ignored) {
            // 没备弹时 TaCZ 内部会拒绝，忽略即可
        }
    }

    /**
     * 开一枪，返回 TaCZ 的原始结果名（SUCCESS / COOL_DOWN / NOT_DRAW / NEED_BOLT / IS_RELOADING / IS_SPRINTING …）。
     * <p>
     * 必须区分结果：把 COOL_DOWN 也当成"打不出去"会让 AI 每 20 tick 重新拔一次枪（卡在拔枪动画里永远打不响），
     * 而 NOT_DRAW 则需要立刻重新拔枪。返回 null 表示兼容层不可用。
     */
    public static String shootResult(AiPlayer player) {
        return shootResult(player, player.getXRot(), player.getYRot());
    }

    /**
     * 按指定仰角 / 偏航角开一枪。
     * <p>
     * <b>这两个角度必须传真值</b>：TaCZ 的 {@code shoot(Supplier, Supplier, long)} 里
     * 第一个 Supplier 是 pitch、第二个是 yaw（见 {@code ModernKineticGunItem.shoot} 的
     * {@code setPitchSupplier / setYawSupplier}，以及 {@code ModernKineticGunScriptAPI.shootOnce}
     * 里 {@code pitchSupplier.get() / yawSupplier.get()}）。以前这里传常量 0，
     * 于是每一枪都朝 yaw=0、pitch=0 打 —— 有枪声、也真的生成了 {@code EntityKineticBullet}，
     * 但全飞向同一个固定方向，所以"怎么打都打不到人"。
     *
     * @param pitch 仰角（原版 {@code LivingEntity.getXRot()}，正值朝下）
     * @param yaw   偏航角（原版 {@code LivingEntity.getYRot()}）
     */
    public static String shootResult(AiPlayer player, float pitch, float yaw) {
        Object op = operator(player);
        if (op == null) {
            return null;
        }
        Supplier<Float> pitchSupplier = () -> pitch;
        Supplier<Float> yawSupplier = () -> yaw;
        try {
            // 关键：必须带上"客户端时间戳"。TaCZ 的 shoot 会做两件事：
            //   ① 反作弊：System.currentTimeMillis() - baseTimestamp - ts 超出 ±300ms → NETWORK_FAIL，一枪都不打；
            //   ② 射速：ts - shootTimestamp - 射击间隔 > 0 → COOL_DOWN。
            // 真客户端会先被服务端同步 baseTimestamp；假玩家收不到那次同步，baseTimestamp 恒为 0。
            // 所以不能只把 ts 算成"现在"，那样 ② 会让第一枪就 COOL_DOWN（shootTimestamp 还是 0，
            // ts 却是几十亿），表现同样是"给了枪和弹也一枪不放"。
            // 正确做法：给假玩家补一个自己的 base 时间戳，ts 取"相对 base 的毫秒数"，
            // 这样 ① 的差值恒为 0，② 也是从 0 开始正常累加。
            long base = baseTimestamp(op);
            if (base <= 0L) {
                base = System.currentTimeMillis();
                Object data = dataHolder(op);
                if (data != null && fBaseTimestamp != null) {
                    fBaseTimestamp.setLong(data, base);
                }
            }
            long ts = System.currentTimeMillis() - base;
            Object result = mShootTs != null
                    ? mShootTs.invoke(op, pitchSupplier, yawSupplier, ts)
                    : mShoot.invoke(op, pitchSupplier, yawSupplier);
            return result == null ? null : result.toString();
        } catch (Throwable t) {
            // 以前这里完全吞掉异常，"AI 不开枪"就变成了没有任何线索的悬案。
            // 现在只打印第一次（同一个进程里失败通常是同一个原因），避免每 tick 刷屏。
            if (!SHOOT_ERROR_LOGGED) {
                SHOOT_ERROR_LOGGED = true;
                AiPlayerMod.LOGGER.warn("[aiplayer] TaCZ 射击调用异常（后续同类异常不再打印）: {}", t.toString(), t);
            }
            return null;
        }
    }

    /** 射击反射异常的打印开关：只打第一次 */
    private static boolean SHOOT_ERROR_LOGGED;

    /** 取 ShooterDataHolder（假玩家的枪械状态全在这个对象里） */
    private static Object dataHolder(Object operator) {
        try {
            return mDataHolder == null || operator == null ? null : mDataHolder.invoke(operator);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 读 ShooterDataHolder.baseTimestamp（真客户端会同步它，假玩家恒为 0） */
    private static long baseTimestamp(Object operator) {
        Object data = dataHolder(operator);
        if (data == null || fBaseTimestamp == null) {
            return 0L;
        }
        try {
            return fBaseTimestamp.getLong(data);
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    /**
     * 开一枪。
     *
     * @return true 表示真的打出去了（TaCZ 返回 SUCCESS）
     */
    public static boolean shoot(AiPlayer player) {
        return "SUCCESS".equals(shootResult(player));
    }

    /** 射击冷却中（射速限制）：这时扣扳机必然返回 COOL_DOWN，别计入失败 */
    public static boolean shootOnCooldown(AiPlayer player) {
        Object op = operator(player);
        if (op == null || mShootCd == null) {
            return false;
        }
        try {
            Object cd = mShootCd.invoke(op);
            return cd instanceof Number n && n.longValue() > 0L;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 拉栓（狙击枪打完一发后必须拉栓，否则下一发返回 NEED_BOLT） */
    public static void bolt(AiPlayer player) {
        Object op = operator(player);
        if (op == null || mBolt == null) {
            return;
        }
        try {
            mBolt.invoke(op);
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    /** 手上这把枪的弹匣容量（读不到返回 0） */
    private static int magSizeOf(ItemStack stack) {
        Object gun = gunOf(stack);
        if (gun == null) {
            return 0;
        }
        try {
            Identifier id = identifierOf(gun, stack);
            if (id == null || mGunIndex == null || mGunDataOfIndex == null || mAmmoAmount == null) {
                return 0;
            }
            Object opt = mGunIndex.invoke(null, id);
            if (!(opt instanceof Optional<?> o) || o.isEmpty()) {
                return 0;
            }
            Object data = mGunDataOfIndex.invoke(o.get());
            Object n = data == null ? null : mAmmoAmount.invoke(data);
            return n instanceof Number num ? num.intValue() : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /**
     * 创造模式弹匣：把手上这把枪的弹匣直接填满，不消耗背包弹药。
     * <p>
     * 只动枪物品里的弹匣数（{@code setCurrentAmmoCount}），不碰背包、不生成弹药物品，
     * 所以 AI 的弹药越打越多是不存在的——背包里的备弹原样保留，只是永远用不上。
     *
     * @return true 表示这一次真的填了（调用方据此跳过换弹流程）
     */
    public static boolean refillMagazine(AiPlayer player) {
        if (!LOADED || mSetAmmoCount == null) {
            return false;
        }
        ItemStack held = player.getMainHandItem();
        Object gun = gunOf(held);
        if (gun == null) {
            return false;
        }
        int cap = magSizeOf(held);
        if (cap <= 0 || ammoOf(gun, held) >= cap) {
            return false;
        }
        try {
            mSetAmmoCount.invoke(gun, held, cap);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 手上这把枪的弹匣剩几发 */
    public static int loadedAmmo(AiPlayer player) {
        ItemStack held = player.getMainHandItem();
        Object gun = gunOf(held);
        return gun == null ? 0 : ammoOf(gun, held);
    }

    /** 背包里有没有这把枪能用的备弹（能换弹） */
    public static boolean hasReserveAmmo(AiPlayer player) {
        ItemStack held = player.getMainHandItem();
        Object gun = gunOf(held);
        return gun != null && reserveAmmoOf(gun, player, held);
    }

    public static boolean isReloading(AiPlayer player) {
        Object op = operator(player);
        if (op == null || mReloadState == null || mStateType == null || mIsReloading == null) {
            return false;
        }
        try {
            Object state = mReloadState.invoke(op);
            if (state == null) {
                return false;
            }
            Object type = mStateType.invoke(state);
            return type != null && Boolean.TRUE.equals(mIsReloading.invoke(type));
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 还在拔枪 / 切枪动画里 */
    public static boolean drawing(AiPlayer player) {
        Object op = operator(player);
        if (op == null || mDrawCd == null) {
            return false;
        }
        try {
            Object cd = mDrawCd.invoke(op);
            return cd instanceof Number n && n.longValue() > 0L;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean shouldUseGun(double distance) {
        return distance > MIN_GUN_DISTANCE;
    }

    public static boolean shouldSwitchToMelee(double distance) {
        return distance < MELEE_SWITCH_DISTANCE;
    }

    // ------------------------------------------------------------------
    // LLM / 指令造枪：补上 TaCZ 必需的 NBT
    // ------------------------------------------------------------------

    /**
     * 用物品 id 造一把“完整”的 TaCZ 枪（带枪种、弹匣数据）。
     * <p>
     * LLM 只会写 {@code command: "give @s tacz:modern_kinetic_gun"}，原版 /give 造出来的只是一把
     * 没有枪种 NBT 的空壳，TaCZ 反查 IGun 后不知道该是哪把枪，表现就是“枪在手上但打不响”。
     * 这里把枪种 id 和弹匣一起写进物品，等价于玩家从创造栏里拖出来的正常枪。
     *
     * @param itemId 形如 {@code tacz:modern_kinetic_gun} 的物品 id
     * @return 带 NBT 的枪；兼容层不可用 / id 不对时返回空栈
     */
    public static ItemStack createGunStack(String itemId) {
        if (!LOADED || itemId == null) {
            return ItemStack.EMPTY;
        }
        try {
            Identifier itemKey = Identifier.tryParse(itemId);
            if (itemKey == null) {
                return ItemStack.EMPTY;
            }
            Item item = BuiltInRegistries.ITEM.getValue(itemKey);
            if (item == null) {
                return ItemStack.EMPTY;
            }
            ItemStack stack = new ItemStack(item);
            Object gun = gunOf(stack);
            if (gun == null) {
                return ItemStack.EMPTY;
            }
            // 枪包一般共用一个物品 id，真正的枪种要另外指定；如果这个物品本身就是某把枪，就直接用它
            Identifier gunId = itemId.equals("tacz:modern_kinetic_gun")
                    ? pickGunId() : Identifier.tryParse(itemId);
            if (gunId == null) {
                gunId = pickGunId();
            }
            if (gunId == null) {
                return ItemStack.EMPTY;
            }
            stampGun(gun, stack, gunId);
            return stack;
        } catch (Throwable ignored) {
            return ItemStack.EMPTY;
        }
    }

    /**
     * 拦截 {@code give} 指令里的 TaCZ 枪：用带 NBT 的枪替换原版空壳。
     * <p>
     * 只接管“目标能解析 + 物品确实是枪”的情况，弹药 / 配件 / 普通物品继续走原版指令，
     * 避免影响其它行为。
     *
     * @return true 表示已接管执行，调用方不要再执行原指令
     */
    public static boolean tryHandleGunGive(AiPlayer self, String rawCommand) {
        if (!LOADED || rawCommand == null) {
            return false;
        }
        String cmd = rawCommand.trim();
        if (cmd.startsWith("/")) {
            cmd = cmd.substring(1).trim();
        }
        String[] t = cmd.split("\\s+");
        if (t.length < 3 || !"give".equalsIgnoreCase(t[0])) {
            return false;
        }
        String itemId = t[2];
        if (!itemId.startsWith("tacz:")) {
            return false;
        }
        Identifier itemKey = Identifier.tryParse(itemId);
        if (itemKey == null) {
            return false;
        }
        Item item = BuiltInRegistries.ITEM.getValue(itemKey);
        if (item == null) {
            return false;
        }
        ItemStack probe = new ItemStack(item);
        if (!isGunItem(probe)) {
            return false;   // 弹药 / 弹匣 / 配件交给原版
        }
        ItemStack gunStack = createGunStack(itemId);
        if (gunStack.isEmpty()) {
            return false;
        }
        int count = 1;
        if (t.length >= 4) {
            try {
                count = Math.max(1, Math.min(8, Integer.parseInt(t[3])));
            } catch (NumberFormatException ignored) {
                count = 1;
            }
        }
        String target = t[1];
        if ("@s".equals(target) || "@p".equals(target) || self.getScoreboardName().equals(target)) {
            for (int i = 0; i < count; i++) {
                ItemStack copy = gunStack.copy();
                if (!self.getInventory().add(copy)) {
                    self.drop(copy, false, net.minecraft.util.Prediction.PREDICTED);
                }
            }
            // 顺带把同口径弹药补足：枪有了 NBT，但没弹依然打不响
            String summary = grantStarterGun(self);
            AiPlayerMod.LOGGER.info("[aiplayer] LLM 造枪已补 NBT：{} ×{}｜{}", itemId, count, summary);
            return true;
        }
        // 给别人：解析在线玩家，找不到就交给原版报错
        var server = self.level().getServer();
        if (server == null) {
            return false;
        }
        var targetPlayer = server.getPlayerList().getPlayerByName(target);
        if (targetPlayer == null) {
            return false;
        }
        for (int i = 0; i < count; i++) {
            ItemStack copy = gunStack.copy();
            if (!targetPlayer.getInventory().add(copy)) {
                targetPlayer.drop(copy, false, net.minecraft.util.Prediction.PREDICTED);
            }
        }
        AiPlayerMod.LOGGER.info("[aiplayer] LLM 造枪已补 NBT：{} ×{} → {}", itemId, count, target);
        return true;
    }

    // ------------------------------------------------------------------
    // 反射工具
    // ------------------------------------------------------------------

    private static boolean isGunStatic(ItemStack stack) {
        return !stack.isEmpty() && gunOf(stack) != null;
    }

    /** 这个物品是不是 TaCZ 枪（静态版，给物品栏整理用：枪不能丢） */
    public static boolean isGunItem(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        if (LOADED) {
            return gunOf(stack) != null;
        }
        // 反射没接上（tacz 版本对不上）时按物品名兜底，至少别把枪当垃圾扔掉
        return stack.getItem().getDescriptionId().contains("tacz");
    }

    private static Object gunOf(ItemStack stack) {
        if (!LOADED || stack.isEmpty() || mIGunOrNull == null) {
            return null;
        }
        try {
            return mIGunOrNull.invoke(null, stack);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object operator(AiPlayer player) {
        if (!LOADED || mFromLiving == null) {
            return null;
        }
        try {
            return mFromLiving.invoke(null, player);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int ammoOf(Object gun, ItemStack stack) {
        try {
            Object n = mCurrentAmmo.invoke(gun, stack);
            return n instanceof Number num ? num.intValue() : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static boolean reserveAmmoOf(Object gun, AiPlayer player, ItemStack stack) {
        try {
            // 第三个参数必须是 true：TaCZ 的 hasInventoryAmmo(entity, stack, false) 在
            // "枪用弹匣装填"时直接返回 false（它只回答"这枪是不是从背包直接吃弹"），
            // 传 false 会让我们把所有弹匣枪都判成"没有备弹" —— 表现就是给足了子弹也不开枪。
            return Boolean.TRUE.equals(mHasInvAmmo.invoke(gun, player, stack, true));
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 伤害 × 射速 / 60（每秒伤害），查不到资源时退化成射速本身 */
    private static double dpsOf(Object gun, ItemStack stack) {
        int rpm = 0;
        try {
            Object n = mRpm.invoke(gun, stack);
            rpm = n instanceof Number num ? num.intValue() : 0;
        } catch (Throwable ignored) {
            // 忽略
        }
        float damage = damageOf(gun, stack);
        if (damage <= 0.0F) {
            return rpm * 0.1D;
        }
        return damage * Math.max(1, rpm) / 60.0D;
    }

    private static float damageOf(Object gun, ItemStack stack) {
        String key = gunIdOf(gun, stack);
        if (key == null) {
            return 0.0F;
        }
        Float cached = DAMAGE_CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        float damage = 0.0F;
        try {
            Identifier id = identifierOf(gun, stack);
            if (id != null && mGunIndex != null && mBulletData != null && mDamage != null) {
                Object opt = mGunIndex.invoke(null, id);
                if (opt instanceof Optional<?> o && o.isPresent()) {
                    Object bullet = mBulletData.invoke(o.get());
                    if (bullet != null) {
                        Object d = mDamage.invoke(bullet);
                        if (d instanceof Number num) {
                            damage = num.floatValue();
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            // 资源索引还没加载好：先按 0 处理，下次再查
        }
        DAMAGE_CACHE.put(key, damage);
        return damage;
    }

    private static Identifier identifierOf(Object gun, ItemStack stack) {
        try {
            Object v = mGunId.invoke(gun, stack);
            return v instanceof Identifier id ? id : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String gunIdOf(Object gun, ItemStack stack) {
        Identifier id = identifierOf(gun, stack);
        return id == null ? null : id.toString();
    }

    /** 这把枪要什么口径的弹（GunData.ammoId），查不到返回 null */
    private static String requiredAmmoId(Object gun, ItemStack stack) {
        try {
            Identifier gunId = identifierOf(gun, stack);
            if (gunId == null || mGunIndex == null || mGunDataOfIndex == null || mAmmoIdOfGun == null) {
                return null;
            }
            Object opt = mGunIndex.invoke(null, gunId);
            if (!(opt instanceof java.util.Optional<?> o) || o.isEmpty()) {
                return null;
            }
            Object data = mGunDataOfIndex.invoke(o.get());
            Object ammo = data == null ? null : mAmmoIdOfGun.invoke(data);
            return ammo == null ? null : ammo.toString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 背包里和这把枪口径匹配的弹药总发数（用来区分"没子弹"和"子弹口径不对"） */
    private static int countMatchingAmmo(AiPlayer player, ItemStack gunStack) {
        if (mIAmmoOrNull == null || mIsAmmoOfGun == null) {
            return -1;
        }
        Inventory inv = player.getInventory();
        int total = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) {
                continue;
            }
            try {
                Object ammo = mIAmmoOrNull.invoke(null, s);
                if (ammo != null && Boolean.TRUE.equals(mIsAmmoOfGun.invoke(ammo, s, gunStack))) {
                    total += s.getCount();
                }
            } catch (Throwable ignored) {
                // 单个格子读失败就跳过，不影响总数
            }
        }
        return total;
    }
}
