package com.healthytiffin.inventory
import org.junit.Assert.*
import org.junit.Test
class VoiceQuantityTest {
    @Test fun parsesExplicitAmount(){assertEquals(2.4,VoiceQuantity.parse("change to 2.4 kilograms")!!,0.001)}
    @Test fun parsesSpokenDecimal(){assertEquals(2.4,VoiceQuantity.parse("two point four kilograms")!!,0.001)}
    @Test fun refusesMultipleQuantities(){assertNull(VoiceQuantity.parse("four bags of five kilograms"));assertNull(VoiceQuantity.parse("4 bags of 5 kilograms"))}
    @Test fun refusesNegative(){assertNull(VoiceQuantity.parse("minus two"));assertNull(VoiceQuantity.parse("-2"))}
    @Test fun allowsExplicitZero(){assertEquals(0.0,VoiceQuantity.parse("zero bags")!!,0.0)}
}
