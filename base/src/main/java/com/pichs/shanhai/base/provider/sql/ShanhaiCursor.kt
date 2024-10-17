package com.pichs.shanhai.base.provider.sql

import android.database.AbstractCursor

class ShanhaiCursor<T>(var key: String, var result: T?) : AbstractCursor() {

    override fun getCount(): Int {
        return 1
    }

    override fun getColumnNames(): Array<String> {
        return arrayOf(key)
    }

    override fun getString(column: Int): String {
        return result as String
    }

    override fun getShort(column: Int): Short {
        return result as Short
    }

    override fun getInt(column: Int): Int {
        return result as Int
    }

    override fun getLong(column: Int): Long {
        return result as Long
    }

    override fun getFloat(column: Int): Float {
        return result as Float
    }

    override fun getDouble(column: Int): Double {
        return result as Double
    }

    override fun isNull(column: Int): Boolean {
        return result == null
    }
}