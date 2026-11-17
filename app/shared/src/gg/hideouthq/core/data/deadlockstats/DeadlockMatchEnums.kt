package gg.hideouthq.core.data.deadlockstats

enum class MatchMode {
    Unranked,
    PrivateLobby,
    CoopBot,
    Ranked,
    ServerTest,
    Tutorial,
    HeroLabs,
    NewPlayerPlacement,
    Unknown,
    ;

    companion object {
        fun ofCode(code: Int?): MatchMode = when (code) {
            1 -> Unranked
            2 -> PrivateLobby
            3 -> CoopBot
            4 -> Ranked
            5 -> ServerTest
            6 -> Tutorial
            7 -> HeroLabs
            8 -> NewPlayerPlacement
            else -> Unknown
        }
    }
}

enum class PlayerMatchOutcome {
    Win,
    Loss,
    Penalized,
    PenalizedParty,
    NotScored,
    Unknown,
    ;

    companion object {
        fun ofCode(code: Int?): PlayerMatchOutcome = when (code) {
            1 -> Win
            2 -> Loss
            3 -> Penalized
            4 -> PenalizedParty
            5 -> NotScored
            else -> Unknown
        }
    }
}
