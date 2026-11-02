package com.samosaai.masala.ui.character

import com.samosaai.masala.agent.CommandState

/** Sam's eight animated states (states board). */
enum class CharacterState(val face: Expression) {
    HELLO(Expression.HAPPY),
    LISTENING(Expression.LISTENING),
    THINKING(Expression.THINKING),
    SPEAKING(Expression.HAPPY),
    DONE(Expression.DELIGHTED),
    SORRY(Expression.SORRY),
    SLEEPING(Expression.SLEEPY),
    SEARCHING(Expression.CURIOUS),
}

/** Tool steps that mean Masala is looking something up, which Sam shows as searching. */
private val SEARCH_STEPS = Regex("""search|find|fetch|read_file|glob|grep|wiki_read|memory_read""", RegexOption.IGNORE_CASE)

/**
 * What Sam shows for a command (plan UI refresh U4): listening while the mic is on; thinking while the
 * model or a tool runs, searching when the latest step looks something up; speaking while a reply is
 * read aloud; done for a reply; sorry for an error (`CommandState.Error`); asleep on a quiet night when
 * nothing is going on; hello otherwise.
 */
fun characterStateFor(command: CommandState, speaking: Boolean, night: Boolean): CharacterState = when (command) {
    CommandState.Listening, CommandState.ListeningPaused -> CharacterState.LISTENING
    is CommandState.Thinking -> if (command.steps.lastOrNull()?.let(SEARCH_STEPS::containsMatchIn) == true) CharacterState.SEARCHING else CharacterState.THINKING
    is CommandState.Reply -> if (speaking) CharacterState.SPEAKING else CharacterState.DONE
    is CommandState.Error -> CharacterState.SORRY
    CommandState.Idle, CommandState.Input -> if (night) CharacterState.SLEEPING else CharacterState.HELLO
}
