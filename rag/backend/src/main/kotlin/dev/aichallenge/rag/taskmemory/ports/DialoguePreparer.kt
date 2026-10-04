package dev.aichallenge.rag.taskmemory.ports

import dev.aichallenge.rag.taskmemory.models.*

/** Один вызов уточняет поисковый вопрос и предлагает обновление памяти; не отвечает по книге. */
interface DialoguePreparer { fun prepare(context: DialogueContext): PreparationTrace }
