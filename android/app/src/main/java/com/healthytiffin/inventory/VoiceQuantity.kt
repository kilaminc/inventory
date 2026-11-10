package com.healthytiffin.inventory

/** Extract only an unambiguous quantity. The operator sees it before saving. */
object VoiceQuantity {
    fun parse(text:String):Double? {
        val normalized=text.lowercase().replace(",","").trim()
        val numbers=Regex("(?<![a-z0-9])-?\\d+(?:\\.\\d+)?").findAll(normalized).map {it.value.toDouble()}.toList()
        if(numbers.size==1)return numbers[0].takeIf {it.isFinite()&&it>=0}
        if(numbers.isNotEmpty())return null
        val units=mapOf("zero" to 0,"one" to 1,"two" to 2,"three" to 3,"four" to 4,"five" to 5,"six" to 6,"seven" to 7,"eight" to 8,"nine" to 9,"ten" to 10,"eleven" to 11,"twelve" to 12)
        val words=normalized.split(Regex("\\s+"))
        if("minus" in words || "negative" in words)return null
        val found=words.mapIndexedNotNull {i,w->units[w]?.let {i to it}}
        if(found.size==1)return found[0].second.toDouble()
        if(found.size==2 && found[1].first==found[0].first+2 && words[found[0].first+1]=="point" && found[1].second<10)
            return found[0].second+found[1].second/10.0
        return null
    }
}
