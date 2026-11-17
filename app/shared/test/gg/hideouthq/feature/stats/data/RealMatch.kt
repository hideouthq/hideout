package gg.hideouthq.feature.stats.data

import gg.hideouthq.core.data.deadlockstats.ItemAsset
import gg.hideouthq.core.data.deadlockstats.MatchDeathEvent
import gg.hideouthq.core.data.deadlockstats.MatchDetail
import gg.hideouthq.core.data.deadlockstats.MatchItemEvent
import gg.hideouthq.core.data.deadlockstats.MatchObjectiveEvent
import gg.hideouthq.core.data.deadlockstats.MatchParticipant
import gg.hideouthq.core.data.deadlockstats.MatchPlayerStatSnapshot
import gg.hideouthq.core.data.deadlockstats.MidBossEvent
import gg.hideouthq.core.model.Hero

internal object RealMatch {
    const val Id = 118_933_077L
    const val Mugi = 1_157_792_213L
    const val Duration = 2471

    private class Snap(val time: Int, val souls: Int, val damage: Int, val healing: Int, val taken: Int, val mitigated: Int, val boss: Int, val hit: Int, val missed: Int, val bullets: Int, val crits: Int, val creeps: Int, val neutrals: Int)

    private fun snap(time: Int, souls: Int, damage: Int, healing: Int, taken: Int, mitigated: Int, boss: Int, hit: Int, missed: Int, bullets: Int, crits: Int, creeps: Int, neutrals: Int) =
        Snap(time, souls, damage, healing, taken, mitigated, boss, hit, missed, bullets, crits, creeps, neutrals)

    private fun death(time: Int, killer: Int, duration: Int) = MatchDeathEvent(gameTimeS = time, killerPlayerSlot = killer, deathDurationS = duration)

    private fun objective(slot: Int, owner: Int, time: Int) = MatchObjectiveEvent(teamObjectiveId = slot, team = owner, destroyedTimeS = time)

    private fun item(time: Int, id: Long, upgrade: Int, sold: Int, flags: Int, info: Int) =
        MatchItemEvent(gameTimeS = time, itemId = id, upgradeId = upgrade, soldTimeS = sold, flags = flags.toLong(), upgradeInfo = info.toLong())

    private fun player(
        account: Long, slot: Int, team: Int, hero: Int, k: Int, d: Int, a: Int, souls: Int, lastHits: Int, denies: Int, level: Int,
        last: Snap, series: List<Pair<Int, Pair<Int, Int>>>, deaths: List<MatchDeathEvent>,
    ) = MatchParticipant(
        accountId = account, heroId = hero, playerSlot = slot, team = team, kills = k, deaths = d, assists = a, netWorth = souls,
        lastHits = lastHits, denies = denies, level = level, deathDetails = deaths,
        statSnapshots = series.map { (time, values) ->
            if (time == last.time) {
                MatchPlayerStatSnapshot(
                    time, netWorth = last.souls, playerDamage = last.damage, playerHealing = last.healing, playerDamageTaken = last.taken,
                    damageMitigated = last.mitigated, bossDamage = last.boss, shotsHit = last.hit, shotsMissed = last.missed,
                    heroBulletsHit = last.bullets, heroBulletsHitCrit = last.crits, creepKills = last.creeps, neutralKills = last.neutrals,
                )
            } else {
                MatchPlayerStatSnapshot(time, netWorth = values.first, playerDamage = values.second)
            }
        },
    )

    val players = listOf(
        player(1058516575, slot = 10, team = 1, hero = 81, k = 9, d = 5, a = 20, souls = 48244, lastHits = 238, denies = 1, level = 35,
            last = snap(2471, 48244, 43702, 23356, 41123, 27719, 4677, 638, 809, 275, 68, 99, 53),
            series = listOf(180 to (2323 to 1039), 360 to (3906 to 1616), 540 to (6792 to 3873), 720 to (9182 to 5929), 900 to (13363 to 7840), 1200 to (17134 to 12235), 1500 to (25521 to 15027), 1800 to (32132 to 20789), 2100 to (39579 to 28262), 2400 to (43285 to 40682), 2471 to (48244 to 43702)),
            deaths = listOf(death(227, 5, 8), death(685, 6, 21), death(1170, 1, 37), death(2194, 4, 80), death(2333, 4, 83)),
        ),
        player(1096148131, slot = 6, team = 0, hero = 19, k = 13, d = 9, a = 13, souls = 44747, lastHits = 171, denies = 2, level = 34,
            last = snap(2471, 44747, 36478, 17120, 53101, 23169, 2662, 1707, 2721, 203, 21, 75, 41),
            series = listOf(180 to (1784 to 853), 360 to (3468 to 1961), 540 to (5321 to 3688), 720 to (8516 to 6314), 900 to (11840 to 8148), 1200 to (23390 to 14317), 1500 to (27356 to 16207), 1800 to (31795 to 24898), 2100 to (35926 to 31068), 2400 to (43273 to 36383), 2471 to (44747 to 36478)),
            deaths = listOf(death(122, 11, 8), death(446, 10, 13), death(582, 10, 17), death(1004, 7, 31), death(1313, 12, 61), death(1566, 12, 58), death(1995, 9, 75), death(2107, 7, 78), death(2405, 8, 85)),
        ),
        player(80094580, slot = 4, team = 0, hero = 52, k = 9, d = 8, a = 10, souls = 48014, lastHits = 230, denies = 3, level = 35,
            last = snap(2471, 48014, 57141, 32374, 61548, 18055, 2446, 1370, 850, 706, 115, 110, 21),
            series = listOf(180 to (1756 to 1425), 360 to (4008 to 3414), 540 to (5464 to 5491), 720 to (9852 to 9259), 900 to (13072 to 13143), 1200 to (18965 to 15073), 1500 to (29829 to 20170), 1800 to (37027 to 34307), 2100 to (38965 to 48390), 2400 to (46497 to 55883), 2471 to (48014 to 57141)),
            deaths = listOf(death(310, 9, 8), death(482, 9, 14), death(747, 9, 23), death(1163, 8, 37), death(1807, 9, 90), death(2021, 11, 76), death(2110, 9, 78), death(2464, 7, 85)),
        ),
        player(430678819, slot = 5, team = 0, hero = 80, k = 6, d = 11, a = 10, souls = 42734, lastHits = 182, denies = 6, level = 34,
            last = snap(2471, 42734, 30946, 14876, 44258, 28519, 5602, 2552, 1487, 235, 67, 113, 36),
            series = listOf(180 to (1728 to 1152), 360 to (4252 to 2613), 540 to (5570 to 4885), 720 to (8645 to 6992), 900 to (12311 to 9357), 1200 to (17241 to 12328), 1500 to (24753 to 15912), 1800 to (28462 to 22679), 2100 to (34645 to 27504), 2400 to (40981 to 30682), 2471 to (42734 to 30946)),
            deaths = listOf(death(169, 10, 8), death(455, 10, 13), death(563, 11, 17), death(1078, 9, 34), death(1168, 8, 37), death(1662, 8, 63), death(1791, 10, 70), death(1953, 7, 74), death(2091, 9, 77), death(2189, 7, 80), death(2414, 8, 85)),
        ),
        player(180116887, slot = 1, team = 0, hero = 20, k = 6, d = 4, a = 18, souls = 48063, lastHits = 295, denies = 4, level = 35,
            last = snap(2471, 48063, 50967, 49757, 63390, 25529, 11551, 5967, 6380, 3046, 421, 189, 31),
            series = listOf(180 to (1763 to 580), 360 to (3433 to 1814), 540 to (5511 to 3274), 720 to (10220 to 5075), 900 to (14342 to 6677), 1200 to (23353 to 13463), 1500 to (27505 to 19140), 1800 to (33849 to 31511), 2100 to (36779 to 41738), 2400 to (45802 to 50906), 2471 to (48063 to 50967)),
            deaths = listOf(death(588, 7, 18), death(1358, 9, 65), death(1837, 7, 71), death(2450, 9, 85)),
        ),
        player(849720871, slot = 2, team = 0, hero = 31, k = 2, d = 13, a = 13, souls = 39889, lastHits = 140, denies = 1, level = 33,
            last = snap(2471, 39889, 31404, 3990, 43095, 13463, 2209, 1137, 962, 226, 41, 82, 34),
            series = listOf(180 to (1611 to 658), 360 to (3594 to 2088), 540 to (5763 to 2814), 720 to (9080 to 5901), 900 to (12081 to 8164), 1200 to (17489 to 12475), 1500 to (22204 to 16260), 1800 to (28811 to 20436), 2100 to (32878 to 26407), 2400 to (38334 to 31393), 2471 to (39889 to 31404)),
            deaths = listOf(death(99, 12, 8), death(473, 9, 14), death(669, 12, 20), death(783, 10, 24), death(884, 10, 27), death(1174, 12, 37), death(1319, 11, 44), death(1477, 8, 53), death(1679, 9, 64), death(1884, 9, 72), death(2064, 10, 77), death(2156, 7, 79), death(2423, 11, 85)),
        ),
        player(477962985, slot = 7, team = 1, hero = 84, k = 10, d = 9, a = 18, souls = 47223, lastHits = 252, denies = 5, level = 35,
            last = snap(2471, 47223, 72912, 5512, 41695, 20464, 21313, 3474, 3814, 837, 116, 176, 43),
            series = listOf(180 to (1801 to 964), 360 to (3709 to 2546), 540 to (6153 to 6029), 720 to (9643 to 8506), 900 to (12741 to 12201), 1200 to (17656 to 19351), 1500 to (24347 to 23438), 1800 to (27148 to 32197), 2100 to (36167 to 57324), 2400 to (43379 to 70142), 2471 to (47223 to 72912)),
            deaths = listOf(death(557, 1, 17), death(657, 3, 20), death(855, 5, 27), death(921, 6, 29), death(1164, 1, 37), death(1310, 6, 44), death(1595, 3, 59), death(1681, 6, 64), death(2300, 6, 83)),
        ),
        player(165689557, slot = 12, team = 1, hero = 76, k = 6, d = 5, a = 21, souls = 51606, lastHits = 241, denies = 4, level = 36,
            last = snap(2471, 51606, 41312, 13049, 31325, 18998, 6922, 0, 0, 0, 0, 102, 39),
            series = listOf(180 to (2229 to 511), 360 to (3813 to 2616), 540 to (6035 to 3342), 720 to (8723 to 5542), 900 to (11726 to 8369), 1200 to (18192 to 14471), 1500 to (23554 to 16750), 1800 to (31395 to 27010), 2100 to (42966 to 32853), 2400 to (49785 to 40786), 2471 to (51606 to 41312)),
            deaths = listOf(death(586, 4, 18), death(817, 3, 25), death(1183, 6, 37), death(1353, 3, 46), death(1570, 2, 58)),
        ),
        player(95573234, slot = 9, team = 1, hero = 13, k = 15, d = 9, a = 16, souls = 54218, lastHits = 147, denies = 9, level = 36,
            last = snap(2471, 54218, 54685, 24201, 47734, 16667, 9316, 2815, 3415, 3623, 290, 111, 15),
            series = listOf(180 to (1627 to 519), 360 to (3300 to 1309), 540 to (6268 to 3258), 720 to (8904 to 4412), 900 to (12893 to 8153), 1200 to (16671 to 12637), 1500 to (25701 to 20205), 1800 to (30461 to 29783), 2100 to (39648 to 43741), 2400 to (50415 to 49785), 2471 to (54218 to 54685)),
            deaths = listOf(death(274, 4, 8), death(606, 2, 18), death(668, 4, 20), death(799, 4, 25), death(980, 5, 31), death(1105, 6, 35), death(1365, 5, 47), death(1714, 4, 65), death(2173, 6, 90)),
        ),
        player(164789537, slot = 8, team = 1, hero = 66, k = 6, d = 11, a = 21, souls = 43814, lastHits = 91, denies = 3, level = 34,
            last = snap(2471, 43814, 31500, 65992, 57796, 15486, 12310, 651, 882, 142, 18, 40, 43),
            series = listOf(180 to (1792 to 137), 360 to (3424 to 286), 540 to (5381 to 1049), 720 to (7426 to 2681), 900 to (12000 to 3354), 1200 to (16526 to 8225), 1500 to (23421 to 14249), 1800 to (26787 to 20414), 2100 to (33647 to 24990), 2400 to (40721 to 29040), 2471 to (43814 to 31500)),
            deaths = listOf(death(419, 1, 12), death(551, 3, 16), death(663, 1, 20), death(707, 5, 22), death(935, 1, 29), death(1188, 6, 38), death(1349, 4, 46), death(1593, 4, 59), death(1675, 6, 63), death(1793, 6, 70), death(2190, 3, 80)),
        ),
        player(241465507, slot = 3, team = 0, hero = 78, k = 7, d = 8, a = 16, souls = 48358, lastHits = 205, denies = 0, level = 35,
            last = snap(2471, 48358, 54393, 14110, 39263, 12401, 5002, 379, 235, 129, 0, 148, 32),
            series = listOf(180 to (1635 to 855), 360 to (3362 to 2571), 540 to (5383 to 5266), 720 to (9448 to 8350), 900 to (12715 to 11213), 1200 to (18127 to 19619), 1500 to (25182 to 28919), 1800 to (28530 to 38550), 2100 to (33177 to 44011), 2400 to (44425 to 52435), 2471 to (48358 to 54393)),
            deaths = listOf(death(419, 7, 12), death(592, 7, 18), death(982, 11, 31), death(1524, 10, 55), death(1686, 12, 64), death(1836, 11, 71), death(1943, 9, 74), death(2469, 9, 85)),
        ),
        player(1157792213, slot = 11, team = 1, hero = 86, k = 7, d = 4, a = 29, souls = 43394, lastHits = 109, denies = 0, level = 34,
            last = snap(2471, 43394, 60544, 20938, 41656, 20270, 6264, 322, 252, 78, 21, 92, 4),
            series = listOf(180 to (2380 to 2071), 360 to (3274 to 3873), 540 to (5159 to 7246), 720 to (8450 to 11369), 900 to (11653 to 14201), 1200 to (16236 to 21163), 1500 to (22823 to 24305), 1800 to (26963 to 36010), 2100 to (36068 to 45911), 2400 to (40634 to 58601), 2471 to (43394 to 60544)),
            deaths = listOf(death(240, 5, 8), death(689, 6, 21), death(1177, 3, 37), death(2179, 6, 79)),
        ),
    )
    val objectives = listOf(
        objective(1, 1, 588),
        objective(4, 1, 627),
        objective(1, 0, 661),
        objective(3, 1, 933),
        objective(3, 0, 806),
        objective(7, 0, 1904),
        objective(4, 0, 782),
        objective(5, 1, 958),
        objective(5, 0, 843),
        objective(8, 1, 852),
        objective(7, 1, 1819),
        objective(8, 0, 1208),
        objective(15, 0, 1890),
        objective(12, 1, 0),
        objective(15, 1, 2409),
        objective(14, 1, 0),
        objective(12, 0, 2013),
        objective(14, 0, 1923),
        objective(10, 0, 2107),
        objective(11, 0, 2123),
        objective(9, 0, 2148),
        objective(0, 0, 2470),
        objective(10, 1, 0),
    )
    val midBoss = listOf(MidBossEvent(1, 1, 1873), MidBossEvent(1, 1, 2415))
    val mugiItems = listOf(
        item(12, 3612874202L, 0, 0, 0, 65536),
        item(54, 2829638276L, 1, 892, 1, 65536),
        item(56, 3612874202L, 625022953, 0, 0, 196608),
        item(108, 836615329L, 0, 0, 0, 65536),
        item(138, 968099481L, 1, 1381, 1, 65536),
        item(170, 3389503777L, 0, 0, 0, 65536),
        item(195, 1439347412L, 1, 863, 1, 65536),
        item(261, 3612874202L, 2145320896, 0, 0, 458752),
        item(352, 836615329L, 1468838525, 0, 0, 196608),
        item(359, 1998374645L, 1, 1460, 1, 65536),
        item(406, 2516700978L, 0, 0, 0, 65536),
        item(464, 3776945997L, 1, 599, 1, 65536),
        item(550, 836615329L, 833017340, 0, 0, 458752),
        item(567, 3389503777L, 790281767, 0, 0, 196608),
        item(599, 787198704L, 1, 0, 0, 65536),
        item(616, 3389503777L, 2017776098, 0, 0, 458752),
        item(697, 395867183L, 1, 0, 0, 65536),
        item(863, 2947183272L, 1, 0, 0, 65536),
        item(892, 2566692615L, 1, 0, 0, 65536),
        item(911, 3612874202L, 1552289423, 0, 0, 983040),
        item(1010, 1672893796L, 1, 1010, 1, 65536),
        item(1010, 112198670L, 1, 0, 0, 65536),
        item(1010, 4139877411L, 1, 1250, 1, 65536),
        item(1218, 836615329L, 606713882, 0, 0, 983040),
        item(1250, 334300056L, 1, 0, 0, 65536),
        item(1351, 2163598980L, 1, 0, 0, 65536),
        item(1381, 7409189L, 1, 2035, 1, 65536),
        item(1460, 2121044373L, 1, 0, 0, 65536),
        item(1605, 3389503777L, 2036915186, 0, 0, 983040),
        item(1842, 2516700978L, 1298968051, 0, 0, 196608),
        item(1859, 380806748L, 1, 1859, 1, 65536),
        item(1859, 3261353684L, 1, 0, 0, 65536),
        item(1991, 2516700978L, 1939104455, 0, 0, 458752),
        item(2035, 2519598785L, 1, 0, 0, 65536),
        item(2039, 2081037738L, 1, 2182, 1, 65536),
        item(2182, 3005970438L, 1, 0, 0, 65536),
    )

    val detail = MatchDetail(
        matchId = Id, durationS = Duration, matchOutcome = 0, winningTeam = 1, startTime = 1_792_260_852, gameMode = 1, matchMode = 1,
        players = players.map { if (it.accountId == Mugi) it.copy(items = mugiItems) else it },
        objectives = objectives, midBoss = midBoss,
    )

    val items = listOf(
        ItemAsset(3612874202L, "ability_artist_tether", "Still Life", image = "still_life.png"),
        ItemAsset(2829638276L, "upgrade_endurance", "Extra Regen", category = "vitality", cost = 800),
        ItemAsset(836615329L, "ability_artist_homingbrush", "Living Ink", image = "living_ink.png"),
        ItemAsset(968099481L, "upgrade_improved_spirit", "Extra Spirit", category = "spirit", cost = 800),
        ItemAsset(3389503777L, "ability_artist_rollingprojectile", "Flow State"),
        ItemAsset(1439347412L, "upgrade_mystic_regeneration", "Mystic Regeneration", category = "spirit", cost = 800),
        ItemAsset(1998374645L, "upgrade_magic_burst", "Mystic Burst", category = "spirit", cost = 800),
        ItemAsset(2516700978L, "ability_artist_bamboogrove", "Abstract Landscape"),
        ItemAsset(3776945997L, "upgrade_extra_charge", "Extra Charge", category = "spirit", cost = 800),
        ItemAsset(787198704L, "upgrade_rapid_recharge", "Rapid Recharge", category = "spirit", cost = 3200),
        ItemAsset(395867183L, "upgrade_crackshot", "Mystic Shot", category = "weapon", cost = 1600),
        ItemAsset(2947183272L, "upgrade_resonant_healing", "Radiant Regeneration", category = "spirit", cost = 3200),
        ItemAsset(2566692615L, "upgrade_healing_booster", "Healing Booster", category = "vitality", cost = 1600),
        ItemAsset(1672893796L, "upgrade_grit", "Grit", category = "vitality", cost = 800),
        ItemAsset(112198670L, "upgrade_spirit_bubble", "Spirit Shielding", category = "vitality", cost = 1600),
        ItemAsset(4139877411L, "upgrade_improved_stamina", "Extra Stamina", category = "vitality", cost = 800),
        ItemAsset(334300056L, "upgrade_superior_stamina", "Stamina Mastery", category = "vitality", cost = 3200),
        ItemAsset(2163598980L, "upgrade_tech_purge", "Spirit Resilience", category = "vitality", cost = 3200),
        ItemAsset(7409189L, "upgrade_soaring_spirit", "Improved Spirit", category = "spirit", cost = 1600),
        ItemAsset(2121044373L, "upgrade_magic_shock", "Tankbuster", category = "spirit", cost = 3200),
        ItemAsset(380806748L, "upgrade_magic_tempo", "Compress Cooldown", category = "spirit", cost = 1600),
        ItemAsset(3261353684L, "upgrade_cooldown_reduction", "Superior Cooldown", category = "spirit", cost = 3200),
        ItemAsset(2519598785L, "upgrade_boundless_spirit", "Boundless Spirit", category = "spirit", cost = 6400),
        ItemAsset(2081037738L, "upgrade_magic_vulnerability", "Mystic Vulnerability", category = "spirit", cost = 1600),
        ItemAsset(3005970438L, "upgrade_escalating_exposure", "Escalating Exposure", category = "spirit", cost = 6400),
    )

    val violet = Hero(
        "hero_artist", "Violet", numericId = 86,
        signatureAbilities = mapOf(1 to "ability_artist_homingbrush", 2 to "ability_artist_rollingprojectile", 3 to "ability_artist_tether", 4 to "ability_artist_bamboogrove"),
    )
}
