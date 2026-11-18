package com.ctrlz.gametest;

import com.ctrlz.core.consequence.ConsequenceKind;
import com.ctrlz.core.consequence.ConsequenceOrigin;
import com.ctrlz.core.consequence.ConsequenceRecord;
import com.ctrlz.core.rollback.ConflictType;
import com.ctrlz.server.EffectSnapshots;
import com.ctrlz.server.ServerRestorer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;

import java.util.Arrays;
import java.util.Set;
import java.util.UUID;

/**
 * Status effect capture and restore.
 *
 * <p>Two layers are covered on purpose: {@link EffectSnapshots} (the codec plus the replace semantics)
 * and {@link ServerRestorer} (the exact code path a real undo runs). The restorer tests use a mob
 * because the subject resolver looks players up by uuid, which a mock player does not have.
 */
public final class EffectsGameTest {
	private static final double EPSILON = 1.0E-6;

	@GameTest(structure = "minecraft:empty", maxTicks = 100)
	public void playerEffectsComeBackWithTheRecordedDuration(GameTestHelper helper) {
		Player player = helper.makeMockPlayer(GameType.SURVIVAL);
		player.removeAllEffects();
		add(player, MobEffects.SPEED, 600, 1, true);
		add(player, MobEffects.STRENGTH, 200, 0, false);
		double speedWhileBuffed = player.getAttributeValue(Attributes.MOVEMENT_SPEED);
		byte[] snapshot = EffectSnapshots.encode(player);

		helper.assertTrue(EffectSnapshots.isCaptured(snapshot), "a capture is never empty");
		player.removeAllEffects();
		add(player, MobEffects.SLOWNESS, 40, 4, false);

		helper.assertTrue(EffectSnapshots.apply(player, snapshot), "the payload must be usable");
		assertEffect(helper, player, MobEffects.SPEED, 600, 1, "speed II must return with 600 ticks left");
		assertEffect(helper, player, MobEffects.STRENGTH, 200, 0, "every recorded effect must return");
		helper.assertTrue(player.getEffect(MobEffects.SLOWNESS) == null,
				"an effect that was not in the snapshot must be gone");
		helper.assertTrue(Math.abs(player.getAttributeValue(Attributes.MOVEMENT_SPEED) - speedWhileBuffed) < EPSILON,
				"the speed modifier must be applied once, not stacked: expected " + speedWhileBuffed + " but was "
						+ player.getAttributeValue(Attributes.MOVEMENT_SPEED));
		helper.succeed();
	}

	@GameTest(structure = "minecraft:empty", maxTicks = 100)
	public void anEmptySnapshotClearsEveryEffect(GameTestHelper helper) {
		Player player = helper.makeMockPlayer(GameType.SURVIVAL);
		player.removeAllEffects();
		byte[] empty = EffectSnapshots.encode(player);
		helper.assertTrue(EffectSnapshots.isCaptured(empty), "an empty set is still a real capture");

		add(player, MobEffects.WITHER, 100, 0, false);
		add(player, MobEffects.POISON, 100, 0, false);

		helper.assertTrue(EffectSnapshots.apply(player, empty), "an empty capture must be applicable");
		helper.assertTrue(player.getActiveEffects().isEmpty(),
				"a snapshot without effects has to clear what was gained later");
		helper.assertTrue(!EffectSnapshots.apply(player, new byte[0]),
				"an uncaptured payload must be refused instead of silently clearing everything");
		helper.succeed();
	}

	@GameTest(structure = "minecraft:empty", maxTicks = 100)
	public void nonStandardEffectsAndTheirFlagsSurviveTheRoundTrip(GameTestHelper helper) {
		Pig pig = helper.spawnWithNoFreeWill(EntityType.PIG, new BlockPos(1, 1, 1));
		pig.removeAllEffects();
		// Not part of any hard coded list: only the registry id makes this work.
		pig.forceAddEffect(new MobEffectInstance(MobEffects.CONDUIT_POWER, 333, 3, true, false, false), null);
		pig.forceAddEffect(new MobEffectInstance(MobEffects.DARKNESS, 77, 0, false, true, true), null);
		byte[] snapshot = EffectSnapshots.encode(pig);

		pig.removeAllEffects();
		helper.assertTrue(EffectSnapshots.apply(pig, snapshot), "the payload must be usable");

		MobEffectInstance conduit = pig.getEffect(MobEffects.CONDUIT_POWER);
		helper.assertTrue(conduit != null, "the effect must be back");
		helper.assertTrue(conduit.getDuration() == 333, "duration must be exact, was " + conduit.getDuration());
		helper.assertTrue(conduit.getAmplifier() == 3, "amplifier must be exact");
		helper.assertTrue(conduit.isAmbient(), "the ambient flag must survive");
		helper.assertTrue(!conduit.isVisible(), "the particle flag must survive");
		helper.assertTrue(!conduit.showIcon(), "the icon flag must survive");
		MobEffectInstance darkness = pig.getEffect(MobEffects.DARKNESS);
		helper.assertTrue(darkness != null && darkness.isVisible(), "a debuff is restored like a buff");
		helper.assertTrue(Arrays.equals(snapshot, EffectSnapshots.encode(pig)),
				"re-encoding the restored entity must produce the same payload");
		helper.succeed();
	}

	@GameTest(structure = "minecraft:empty", maxTicks = 100)
	public void theRestorerBringsBackBuffAndDebuffOnAMob(GameTestHelper helper) {
		Pig pig = helper.spawnWithNoFreeWill(EntityType.PIG, new BlockPos(2, 1, 1));
		pig.removeAllEffects();
		add(pig, MobEffects.STRENGTH, 400, 2, false);
		add(pig, MobEffects.POISON, 120, 0, true);
		byte[] before = EffectSnapshots.encode(pig);

		// What the recorded operation left behind: the debuff expired, a buff was gained.
		pig.removeAllEffects();
		add(pig, MobEffects.SPEED, 60, 0, false);
		byte[] after = EffectSnapshots.encode(pig);

		ConsequenceRecord record = effectsRecord(pig.getUUID().toString(), before, after);
		ServerRestorer restorer = new ServerRestorer(helper.getLevel().getServer(), Set.of());
		helper.assertTrue(restorer.check(record) == ConflictType.NONE, "a live target must not conflict");
		helper.assertTrue(restorer.restore(record), "restore must succeed");
		assertEffect(helper, pig, MobEffects.STRENGTH, 400, 2, "the buff must come back exactly");
		assertEffect(helper, pig, MobEffects.POISON, 120, 0, "the debuff must come back exactly");
		helper.assertTrue(pig.getEffect(MobEffects.SPEED) == null,
				"the effect gained after the snapshot must be removed");

		helper.assertTrue(restorer.reapply(record), "reapply must succeed");
		assertEffect(helper, pig, MobEffects.SPEED, 60, 0, "reapply writes the after state");
		helper.assertTrue(pig.getEffect(MobEffects.STRENGTH) == null, "reapply removes the restored buff");
		helper.succeed();
	}

	@GameTest(structure = "minecraft:empty", maxTicks = 100)
	public void aVanishedTargetIsARefusalAndNotASilentSuccess(GameTestHelper helper) {
		Player player = helper.makeMockPlayer(GameType.SURVIVAL);
		player.removeAllEffects();
		add(player, MobEffects.SPEED, 100, 0, false);
		byte[] before = EffectSnapshots.encode(player);
		player.removeAllEffects();
		byte[] after = EffectSnapshots.encode(player);

		ConsequenceRecord ghost = effectsRecord(UUID.randomUUID().toString(), before, after);
		ServerRestorer restorer = new ServerRestorer(helper.getLevel().getServer(), Set.of());
		helper.assertTrue(restorer.check(ghost) == ConflictType.TARGET_MISSING,
				"an entity that no longer exists cannot be restored");
		helper.assertTrue(!restorer.restore(ghost), "restoring it must fail so the transaction is reverted");
		helper.succeed();
	}

	private static ConsequenceRecord effectsRecord(String uuid, byte[] before, byte[] after) {
		return new ConsequenceRecord(1L, 1L, ConsequenceKind.ENTITY_EFFECTS, ConsequenceOrigin.DIRECT, 1,
				"minecraft:overworld", ConsequenceRecord.NO_POSITION, "entity#" + uuid, before, after, 0, 0L,
				System.currentTimeMillis(), "effects");
	}

	private static void add(LivingEntity entity, Holder<MobEffect> effect, int duration, int amplifier,
			boolean ambient) {
		// forceAddEffect is used for the fixture so the levels are exactly what the test asked for.
		entity.forceAddEffect(new MobEffectInstance(effect, duration, amplifier, ambient, !ambient, true), null);
	}

	private static void assertEffect(GameTestHelper helper, LivingEntity entity, Holder<MobEffect> effect,
			int duration, int amplifier, String message) {
		MobEffectInstance instance = entity.getEffect(effect);
		helper.assertTrue(instance != null, message + " (effect missing)");
		helper.assertTrue(instance.getDuration() == duration,
				message + " (duration was " + instance.getDuration() + ")");
		helper.assertTrue(instance.getAmplifier() == amplifier,
				message + " (amplifier was " + instance.getAmplifier() + ")");
	}
}
