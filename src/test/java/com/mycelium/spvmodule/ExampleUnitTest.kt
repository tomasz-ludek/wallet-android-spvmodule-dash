package com.mycelium.spvmodule

import org.junit.Test

import org.junit.Assert.*
import org.junit.Ignore

/**
 * To work on unit tests, switch the Test Artifact in the Build Variants view.
 */
class ExampleUnitTest {
    @Test
    @Ignore("Just trying out stuff ...")
    fun iterateOverTwoCollections() {
        val colOne = hashMapOf("bla" to 4, "foo" to 7)
        val colTwo = listOf(3, 6, 8)
        for(number in colOne.values + colTwo) {
            System.out.println(number)
        }
    }
}