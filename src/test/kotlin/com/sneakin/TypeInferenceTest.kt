package com.sneakin

import com.sneakin.model.TypeInference
import com.sneakin.model.ValueType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TypeInferenceTest {

    @Test
    fun infersFormats() {
        assertEquals(ValueType.NUMERIC, TypeInference.infer("123"))
        assertEquals(ValueType.NUMERIC, TypeInference.infer("-99999"))
        assertEquals(ValueType.NUMERIC, TypeInference.infer("3.14"))
        assertEquals(ValueType.DATE, TypeInference.infer("2026-06-12"))
        assertEquals(ValueType.DATE, TypeInference.infer("2026-06-12T10:30:00Z"))
        assertEquals(ValueType.EMAIL, TypeInference.infer("alice@example.com"))
        assertEquals(ValueType.UUID, TypeInference.infer("550e8400-e29b-41d4-a716-446655440000"))
        assertEquals(ValueType.BOOLEAN, TypeInference.infer("true"))
        assertEquals(ValueType.EMPTY, TypeInference.infer(""))
        assertEquals(ValueType.STRING, TypeInference.infer("hello world"))
    }

    @Test
    fun badFormatAppliesOnlyToRecognizedFormats() {
        assertEquals(true, TypeInference.hasFormat(ValueType.DATE))
        assertEquals(true, TypeInference.hasFormat(ValueType.NUMERIC))
        assertEquals(false, TypeInference.hasFormat(ValueType.STRING))
        assertEquals(false, TypeInference.hasFormat(ValueType.EMPTY))
    }
}
