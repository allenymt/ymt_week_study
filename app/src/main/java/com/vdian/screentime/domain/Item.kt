package com.vdian.screentime.domain

/** 考核项标识。原为枚举，1.1 起改为任意字符串 key，内置项保留同名常量。 */
data class Item(val key: String) {
    companion object {
        val RAISED = Item("RAISED")
        val NEAT_WRITING = Item("NEAT_WRITING")
        val NEAT_WRITING_BAD = Item("NEAT_WRITING_BAD")
        val ENGLISH = Item("ENGLISH")
        val LISTENING_PERFECT = Item("LISTENING_PERFECT")
        val READING = Item("READING")
        val EXERCISE = Item("EXERCISE")
        val HOMEWORK_ACTIVE = Item("HOMEWORK_ACTIVE")
        val HOMEWORK_PASSIVE = Item("HOMEWORK_PASSIVE")
        val PRAISE = Item("PRAISE")
        val COMPLAINT = Item("COMPLAINT")
    }
}
