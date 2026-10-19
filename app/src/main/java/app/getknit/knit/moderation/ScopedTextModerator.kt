package app.getknit.knit.moderation

/**
 * Routes text moderation by conversation scope. Both scopes run the identity-slur word list
 * ([LexicalTextFilter]) first and the ML toxicity classifier ([MlTextModerator]) last; the public Nearby
 * broadcast room adds the profanity word list between them. Profanity filtering is deliberately limited to
 * the public room, while slurs and toxicity apply everywhere — the classifier scores a slur aimed at someone
 * as an insult, which it ignores, so the slur list is what catches one in a DM (ADR 2026-10.d62w). The two
 * scopes share one underlying [MlTextModerator] (wired in the moderation DI module) so the heavy model is
 * loaded at most once.
 */
class ScopedTextModerator(
    private val room: TextModerator,
    private val direct: TextModerator,
) {
    /** Classifies [text]; [isRoom] is true for the Nearby broadcast room, false for DMs and groups. */
    suspend fun classify(
        text: String,
        isRoom: Boolean,
    ): TextVerdict = if (isRoom) room.classify(text) else direct.classify(text)
}
