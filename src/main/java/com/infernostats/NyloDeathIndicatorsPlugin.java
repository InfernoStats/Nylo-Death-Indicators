package com.infernostats;

import com.google.common.annotations.VisibleForTesting;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.*;
import net.runelite.api.gameval.AnimationID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.NpcID;
import net.runelite.api.gameval.*;
import net.runelite.api.kit.KitType;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.callback.Hooks;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.party.PartyMember;
import net.runelite.client.party.PartyService;
import net.runelite.client.party.WSClient;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.util.Text;
import org.apache.commons.lang3.ArrayUtils;

import javax.inject.Inject;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@PluginDescriptor(
    name = "Nylo Death Indicators",
    description = "Hide dead nylos faster"
)
public class NyloDeathIndicatorsPlugin extends Plugin {
  private int partySize = 0;
  private boolean isInNyloRegion = false;
  private final ArrayList<Nylocas> nylos = new ArrayList<>();
  private final ArrayList<Nylocas> deadNylos = new ArrayList<>();
  private final Map<Skill, Integer> fakeXpMap = new EnumMap<>(Skill.class);
  private final Map<Skill, Integer> previousXpMap = new EnumMap<>(Skill.class);

  private static final Set<Integer> CHINCHOMPAS = new HashSet<>(Arrays.asList(
      ItemID.CHINCHOMPA_CAPTURED,
      ItemID.CHINCHOMPA_BIG_CAPTURED,
      ItemID.CHINCHOMPA_BLACK
  ));

  private static final Set<Integer> POWERED_STAVES = new HashSet<>(Arrays.asList(
      ItemID.SANGUINESTI_STAFF,
      ItemID.TOTS,
      ItemID.TOTS_CHARGED,
      ItemID.TOXIC_TOTS_CHARGED,
      ItemID.TOXIC_TOTS_I_CHARGED,
      ItemID.SANGUINESTI_STAFF_OR,
      ItemID.WILD_CAVE_ACCURSED_CHARGED,
      ItemID.WARPED_SCEPTRE,
      ItemID.TUMEKENS_SHADOW,
      ItemID.DEADMAN_BLIGHTED_TUMEKENS_SHADOW
  ));

  private static final Set<Integer> NYLO_MELEE_WEAPONS = new HashSet<>(Arrays.asList(
      ItemID.SWIFT_BLADE, ItemID.JOINT_OF_HAM, ItemID.GOBLIN_RPG,
      ItemID.DRAGON_CLAWS, ItemID.DRAGON_SCIMITAR,
      ItemID.ABYSSAL_BLUDGEON, ItemID.INQUISITORS_MACE,
      ItemID.SARADOMIN_SWORD, ItemID.BLESSED_SARADOMIN_SWORD_DEGRADED,
      ItemID.GHRAZI_RAPIER, ItemID.GHRAZI_RAPIER_OR,
      ItemID.ABYSSAL_WHIP, ItemID.LEAGUE_3_WHIP,
      ItemID.ABYSSAL_WHIP_ICE, ItemID.ABYSSAL_WHIP_LAVA,
      ItemID.ABYSSAL_TENTACLE, ItemID.LEAGUE_3_WHIP_TENTACLE,
      ItemID.BLADE_OF_SAELDOR, ItemID.BLADE_OF_SAELDOR_INFINITE,
      ItemID.BLADE_OF_SAELDOR_INFINITE_DUMMY, ItemID.BLADE_OF_SAELDOR_INFINITE_ITHELL,
      ItemID.BLADE_OF_SAELDOR_INFINITE_IORWERTH, ItemID.BLADE_OF_SAELDOR_INFINITE_TRAHAEARN,
      ItemID.BLADE_OF_SAELDOR_INFINITE_CADARN, ItemID.BLADE_OF_SAELDOR_INFINITE_CRWYS,
      ItemID.BLADE_OF_SAELDOR_INFINITE_MEILYR, ItemID.BLADE_OF_SAELDOR_INFINITE_AMLODD,
      ItemID.BH_DRAGON_CLAWS_CORRUPTED, ItemID.DEADMAN_BLIGHTED_DRAGON_CLAWS, ItemID.VOIDWAKER,
      ItemID.DUAL_MACUAHUITL, ItemID.ELDER_MAUL,
      ItemID.SULPHUR_BLADES, ItemID.GLACIAL_TEMOTLI
  ));

  private static final Set<Integer> MULTIKILL_MELEE_WEAPONS = new HashSet<>(Arrays.asList(
      ItemID.SCYTHE_OF_VITUR_UNCHARGED, ItemID.SCYTHE_OF_VITUR,
      ItemID.SCYTHE_OF_VITUR_UNCHARGED_OR, ItemID.SCYTHE_OF_VITUR_OR,
      ItemID.SCYTHE_OF_VITUR_UNCHARGED_BL, ItemID.SCYTHE_OF_VITUR_BL,
      ItemID.DEADMAN_BLIGHTED_SCYTHE_OF_VITUR, ItemID.DEADMAN_BLIGHTED_SCYTHE_OF_VITUR_UNCHARGED,
      ItemID.DINHS_BULWARK
  ));

  private static final int BARRAGE_ANIMATION = AnimationID.ZAROS_VERTICAL_CASTING_WALKMERGE;
  private static final int DRAGON_FIRE_SHIELD_ANIMATION = AnimationID.QIP_DRAGON_SLAYER_PLAYER_UNLEASHING_FIRE;
  private static final int NYLOCAS_REGION_ID = 13122;

  private final Hooks.RenderableDrawListener drawListener = this::shouldDraw;

  @Inject
  private Client client;

  @Inject
  private ClientThread clientThread;

  @Inject
  private WSClient wsClient;

  @Inject
  private PartyService party;

  @Inject
  private Hooks hooks;

  @Override
  protected void startUp() {
    clientThread.invoke(this::initializePreviousXpMap);

    hooks.registerRenderableDrawListener(drawListener);
    wsClient.registerMessage(NpcDamaged.class);
  }

  @Override
  protected void shutDown() {
    hooks.unregisterRenderableDrawListener(drawListener);
    wsClient.unregisterMessage(NpcDamaged.class);
  }

  private void initializePreviousXpMap() {
    if (client.getGameState() != GameState.LOGGED_IN) {
      previousXpMap.clear();
    } else {
      for (final Skill skill : Skill.values()) {
        previousXpMap.put(skill, client.getSkillExperience(skill));
      }
    }
  }

  @Subscribe
  protected void onGameTick(GameTick event) {
    if (!isInNyloRegion) {
      isInNyloRegion = isInNylocasRegion();
      if (isInNyloRegion) {
        partySize = getParty().size();
      }
    } else {
      isInNyloRegion = isInNylocasRegion();
      if (!isInNyloRegion) {
        this.nylos.clear();
      }
    }

    // Group FakeXP drops and process them every game tick
    for (Map.Entry<Skill, Integer> xp : fakeXpMap.entrySet()) {
      processXpDrop(xp.getKey(), xp.getValue());
    }
    fakeXpMap.clear();

    Iterator<Nylocas> nylocasIterator = deadNylos.iterator();
    while (nylocasIterator.hasNext()) {
      Nylocas nylocas = nylocasIterator.next();
      nylocas.setHidden(nylocas.getHidden() + 1);

      final boolean isDead = nylocas.getNpc().getHealthRatio() == 0;
      if (nylocas.getHidden() > 5 && !isDead) {
        nylocas.setHidden(0);
        nylocasIterator.remove();
      }
    }
  }

  @Subscribe
  protected void onNpcSpawned(NpcSpawned event) {
    if (!isInNyloRegion) {
      return;
    }

    int smSmallHP = -1;
    int smBigHP = -1;
    int bigHP = -1;
    int smallHP = -1;

    switch (this.partySize) {
      case 1:
        bigHP = 16;
        smallHP = 8;
        smSmallHP = 2;
        smBigHP = 3;
        break;
      case 2:
        bigHP = 16;
        smallHP = 8;
        smSmallHP = 3;
        smBigHP = 5;
        break;
      case 3:
        bigHP = 16;
        smallHP = 8;
        smSmallHP = 6;
        smBigHP = 9;
        break;
      case 4:
        bigHP = 19;
        smallHP = 9;
        smSmallHP = 8;
        smBigHP = 12;
        break;
      case 5:
        bigHP = 22;
        smallHP = 11;
        smSmallHP = 10;
        smBigHP = 15;
        break;
    }

    final NPC npc = event.getNpc();
    final int index = npc.getIndex();
    switch (npc.getId()) {
      case NpcID.TOB_NYLOCAS_INCOMING_MELEE:
      case NpcID.TOB_NYLOCAS_INCOMING_RANGED:
      case NpcID.TOB_NYLOCAS_INCOMING_MAGIC:
      case NpcID.TOB_NYLOCAS_INCOMING_MELEE_HARD:
      case NpcID.TOB_NYLOCAS_INCOMING_RANGED_HARD:
      case NpcID.TOB_NYLOCAS_INCOMING_MAGIC_HARD:
        this.nylos.add(new Nylocas(npc, index, smallHP));
        break;
      case NpcID.TOB_NYLOCAS_BIG_INCOMING_MELEE:
      case NpcID.TOB_NYLOCAS_BIG_INCOMING_RANGED:
      case NpcID.TOB_NYLOCAS_BIG_INCOMING_MAGIC:
      case NpcID.TOB_NYLOCAS_BIG_FIGHTING_MELEE:
      case NpcID.TOB_NYLOCAS_BIG_FIGHTING_RANGED:
      case NpcID.TOB_NYLOCAS_BIG_FIGHTING_MAGIC:
      case NpcID.TOB_NYLOCAS_BIG_FIGHTING_MELEE_STORY:
      case NpcID.TOB_NYLOCAS_BIG_FIGHTING_RANGED_STORY:
      case NpcID.TOB_NYLOCAS_BIG_FIGHTING_MAGIC_STORY:
      case NpcID.TOB_NYLOCAS_BIG_INCOMING_MELEE_HARD:
      case NpcID.TOB_NYLOCAS_BIG_INCOMING_RANGED_HARD:
      case NpcID.TOB_NYLOCAS_BIG_INCOMING_MAGIC_HARD:
      case NpcID.TOB_NYLOCAS_BIG_FIGHTING_MELEE_HARD:
      case NpcID.TOB_NYLOCAS_BIG_FIGHTING_RANGED_HARD:
      case NpcID.TOB_NYLOCAS_BIG_FIGHTING_MAGIC_HARD:
        this.nylos.add(new Nylocas(npc, index, bigHP));
        break;
      case NpcID.TOB_NYLOCAS_INCOMING_MELEE_STORY:
      case NpcID.TOB_NYLOCAS_INCOMING_RANGED_STORY:
      case NpcID.TOB_NYLOCAS_INCOMING_MAGIC_STORY:
        this.nylos.add(new Nylocas(npc, index, smSmallHP));
        break;
      case NpcID.TOB_NYLOCAS_BIG_INCOMING_MELEE_STORY:
      case NpcID.TOB_NYLOCAS_BIG_INCOMING_RANGED_STORY:
      case NpcID.TOB_NYLOCAS_BIG_INCOMING_MAGIC_STORY:
        this.nylos.add(new Nylocas(npc, index, smBigHP));
    }
  }

  @Subscribe
  protected void onNpcDespawned(NpcDespawned event) {
    if (!isInNyloRegion) {
      return;
    }

    this.nylos.removeIf((nylo) -> nylo.getNpcIndex() == event.getNpc().getIndex());
    this.deadNylos.removeIf((nylo) -> nylo.getNpcIndex() == event.getNpc().getIndex());
  }

  @Subscribe
  protected void onHitsplatApplied(HitsplatApplied event) {
    if (!isInNyloRegion) {
      return;
    }

    Actor actor = event.getActor();
    if (actor instanceof NPC) {
      final int npcIndex = ((NPC) actor).getIndex();
      final int damage = event.getHitsplat().getAmount();

      for (Nylocas nylocas : this.nylos) {
        if (nylocas.getNpcIndex() != npcIndex) {
          continue;
        }

        if (event.getHitsplat().getHitsplatType() == HitsplatID.HEAL) {
          nylocas.setHp(nylocas.getHp() + damage);
        } else {
          nylocas.setHp(nylocas.getHp() - damage);
        }

        nylocas.setQueuedDamage(Math.max(0, nylocas.getQueuedDamage() - damage));
      }
    }
  }

  @Subscribe
  protected void onNpcDamaged(NpcDamaged event) {
    if (!isInNyloRegion) {
      return;
    }

    PartyMember member = party.getLocalMember();
    if (member != null) {
      // Ignore party messages from yourself, they're already applied
      if (member.getMemberId() == event.getMemberId()) {
        return;
      }
    }

    clientThread.invokeLater(() -> {
      final int npcIndex = event.getNpcIndex();
      final int damage = event.getDamage();

      for (Nylocas nylocas : this.nylos) {
        if (nylocas.getNpcIndex() != npcIndex) {
          continue;
        }

        nylocas.setQueuedDamage(nylocas.getQueuedDamage() + damage);

        if (nylocas.getHp() - nylocas.getQueuedDamage() <= 0) {
          if (deadNylos.stream().noneMatch(deadNylo -> deadNylo.getNpcIndex() == npcIndex)) {
            deadNylos.add(nylocas);
            nylocas.getNpc().setDead(true);
          }
        }
      }
    });
  }

  @Subscribe
  protected void onFakeXpDrop(FakeXpDrop event) {
    final int currentXp = fakeXpMap.getOrDefault(event.getSkill(), 0);
    fakeXpMap.put(event.getSkill(), currentXp + event.getXp());
  }

  @Subscribe
  protected void onStatChanged(StatChanged event) {
    preProcessXpDrop(event.getSkill(), event.getXp());
  }

  private void preProcessXpDrop(Skill skill, int xp) {
    final int xpAfter = client.getSkillExperience(skill);
    final int xpBefore = previousXpMap.getOrDefault(skill, -1);

    previousXpMap.put(skill, xpAfter);

    if (xpBefore == -1 || xpAfter <= xpBefore) {
      return;
    }

    processXpDrop(skill, xpAfter - xpBefore);
  }

  private void processXpDrop(Skill skill, final int xp) {
    if (!isInNylocasRegion()) {
      return;
    }

    int damage = 0;

    Player player = client.getLocalPlayer();
    if (player == null) {
      return;
    }

    PlayerComposition playerComposition = player.getPlayerComposition();
    if (playerComposition == null) {
      return;
    }

    int weaponUsed = playerComposition.getEquipmentId(KitType.WEAPON);
    int attackStyle = client.getVarpValue(VarPlayerID.COM_MODE);

    boolean isBarrageCast = player.getAnimation() == BARRAGE_ANIMATION;
    boolean isDragonFireShield = player.getAnimation() == DRAGON_FIRE_SHIELD_ANIMATION;

    boolean isChinchompa = CHINCHOMPAS.contains(weaponUsed);
    boolean isPoweredStaff = POWERED_STAVES.contains(weaponUsed);

    boolean isDefensiveCast = false;
    if (isBarrageCast && !isPoweredStaff) {
      isDefensiveCast = client.getVarbitValue(VarbitID.AUTOCAST_DEFMODE) == 1;
    } else if (isPoweredStaff) {
      // Manually casting barrage with a powered staff equipped uses the staff's
      // current attack option to decide whether to cast on defensive or not
      isDefensiveCast = attackStyle == 3;
    }

    switch (skill) {
      case MAGIC:
        if (isBarrageCast && !isDefensiveCast) {
          if (xp % 2 == 0) {
            // Ice Barrage casts are always even due to 52 base xp
            damage = (xp - 52) / 2;
          } else {
            // Blood Barrage casts are always odd due to 51 base xp
            damage = (xp - 51) / 2;
          }
          handleAreaOfEffectAttack(damage, player.getInteracting(), true);
          return;
        } else if (isPoweredStaff && !isDefensiveCast) {
          damage = (int) ((double) xp / 2.0D);
        }

        break;
      case ATTACK:
      case STRENGTH:
      case DEFENCE:
        if (MULTIKILL_MELEE_WEAPONS.contains(weaponUsed)) {
          return;
        } else if (NYLO_MELEE_WEAPONS.contains(weaponUsed)) {
          damage = (int) ((double) xp / 4.0D);
        } else if (isDragonFireShield) {
          damage = (int) ((double) xp / 4.0D);
        } else if (isBarrageCast && isDefensiveCast) {
          handleAreaOfEffectAttack(xp, player.getInteracting(), true);
          return;
        } else if (isPoweredStaff && isDefensiveCast) {
          damage = xp;
        }

        break;
      case RANGED:
        if (attackStyle == 3) {
          damage = (int) ((double) xp / 2.0D);
        } else {
          damage = (int) ((double) xp / 4.0D);
        }

        if (isChinchompa) {
          handleAreaOfEffectAttack(damage, player.getInteracting(), false);
          return;
        }
    }

    sendDamage(player, damage);
  }

  private void handleAreaOfEffectAttack(final long hit, Actor interacted, boolean isBarrage) {
    Predicate<Integer> type;
    if (isBarrage) {
      type = NylocasType::isMageNylocas;
    } else {
      type = NylocasType::isRangeNylocas;
    }

    if (interacted instanceof NPC) {
      NPC interactedNPC = (NPC) interacted;
      WorldPoint targetPoint = interactedNPC.getWorldLocation();

      // Filter all nylos within the radius and then
      // Filter all nylos that can be damaged within the radius
      List<Nylocas> clump = this.nylos.stream()
          .filter(nylo -> nylo.getNpc().getWorldLocation().distanceTo(targetPoint) <= 1)
          .filter(nylo -> type.test(nylo.getNpc().getId()))
          .collect(Collectors.toList());

      final int clumpHp = clump.stream()
          .mapToInt(Nylocas::getHp)
          .sum();
      if (clumpHp > hit) {
        return;
      }

      sendClumpDamage(clump);
    }
  }

  private void sendDamage(Player player, int damage) {
    if (damage <= 0) {
      return;
    }

    Actor interacted = player.getInteracting();
    if (interacted instanceof NPC) {
      NPC interactedNPC = (NPC) interacted;
      final int npcIndex = interactedNPC.getIndex();
      final NpcDamaged npcDamaged = new NpcDamaged(npcIndex, damage);

      if (party.isInParty()) {
        clientThread.invokeLater(() -> party.send(npcDamaged));
      }

      onNpcDamaged(npcDamaged);
    }
  }

  private void sendClumpDamage(List<Nylocas> clump) {
    for (Nylocas nylocas : clump) {
      final int npcIndex = nylocas.getNpcIndex();
      final NpcDamaged npcDamaged = new NpcDamaged(npcIndex, nylocas.getHp());

      if (party.isInParty()) {
        clientThread.invokeLater(() -> party.send(npcDamaged));
      }

      onNpcDamaged(npcDamaged);
    }
  }

  public List<String> getParty() {
    List<String> team = new ArrayList<>();

    for (int i = 330; i < 335; i++) {
      team.add(client.getVarcStrValue(i));
    }

    return team.stream()
        .map(Text::sanitize)
        .filter(name -> !name.isEmpty())
        .collect(Collectors.toList());
  }

  private boolean isInNylocasRegion() {
    WorldView wv = client.getTopLevelWorldView();
    if (wv == null) {
      return false;
    }

    int[] regions = wv.getMapRegions();
    if (regions == null || regions.length == 0) {
      return false;
    }

    return ArrayUtils.contains(regions, NYLOCAS_REGION_ID);
  }

  @VisibleForTesting
  boolean shouldDraw(Renderable renderable, boolean drawingUI) {
    if (renderable instanceof NPC) {
      return deadNylos.stream()
          .noneMatch(nylocas -> nylocas.getNpcIndex() == ((NPC) renderable).getIndex());
    } else if (renderable instanceof GraphicsObject) {
      switch (((GraphicsObject) renderable).getId()) {
        case SpotanimID.TOB_NYLOCAS_DEATH_MELEE_STANDARD:
        case SpotanimID.TOB_NYLOCAS_DEATH_RANGED_STANDARD:
        case SpotanimID.TOB_NYLOCAS_DEATH_MAGIC_STANDARD:
        case SpotanimID.TOB_NYLOCAS_DEATH_MELEE_DETONATE:
        case SpotanimID.TOB_NYLOCAS_DEATH_RANGED_DETONATE:
        case SpotanimID.TOB_NYLOCAS_DEATH_MAGIC_DETONATE:
          return false;
      }
    }

    return true;
  }
}
