package com.zack.runnable

import com.zack.runnable.Priority
import com.zack.runnable.PriorityLevel

/**
 * Created by zack on 2019/4/28.
 * 具有等级的runnable
 */
abstract class PriorityRunnable() : Runnable,Comparable<Priority>, Priority {

    private var mPriority : PriorityLevel = PriorityLevel.NORMAL

    constructor(priority: PriorityLevel):this(){
        this.mPriority = priority
    }

    /**
     * 获取等级
     */
    override fun getPriority(): Int = mPriority.level

    /**
     * 设置等级
     */
    override fun setPriority(priority: PriorityLevel) {
        mPriority = priority
    }

    /**
     * 比较等级
     */
    override fun compareTo(other: Priority): Int = getPriority() - other.getPriority()


}