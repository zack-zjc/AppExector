package com.zack.runnable

/**
 * Created by zack on 2019/4/28.
 * 权限等级
 */
interface Priority {

    fun getPriority(): Int

    fun setPriority(priority: PriorityLevel)

}