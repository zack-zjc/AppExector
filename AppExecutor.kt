package com.zack.executor

import android.os.Process
import android.os.StrictMode
import android.util.Log
import java.io.File
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern

/**
 * Created by zack on 2019/2/21.
 * 线程池对象，借鉴glideExecutor
 */
class AppExecutor private constructor(corePoolSize:Int, maximumPoolSize:Int, keepAliveTimeInMs:Long, name:String,
                                      uncaughtThrowableStrategy: UncaughtThrowableStrategy, preventNetworkOperations:Boolean, queue: BlockingQueue<Runnable>)
    : ThreadPoolExecutor(corePoolSize,maximumPoolSize,keepAliveTimeInMs, TimeUnit.MILLISECONDS,queue,
        DefaultThreadFactory(name, uncaughtThrowableStrategy, preventNetworkOperations)){

    private constructor(corePoolSize:Int, maximumPoolSize:Int, name:String,preventNetworkOperations:Boolean)
            :this(corePoolSize,maximumPoolSize,0L,name, UncaughtThrowableStrategy.DEFAULT,preventNetworkOperations,
            PriorityBlockingQueue<Runnable>())

    companion object{

        /**
         * 是否debug模式
         */
        const val DEBUG = true

        /**
         * The default thread count for executors used to disk
         */
        const val TAG = "AppExecutor"
        /**
         * The default thread name prefix for executors used to load/decode/transform data not found in
         * cache.
         */
        const val DEFAULT_NETWORK_EXECUTOR_NAME = "network"
        /**
         * The default thread name prefix for executors used to load/decode/transform data found in
         */
        const val DEFAULT_DISK_EXECUTOR_NAME = "disk"
        /**
         * The default thread name prefix for executors from unlimited thread pool used to
         * load/decode/transform data not found in cache.
         */
        const val SOURCE_UNLIMITED_EXECUTOR_NAME = "source-unlimited"

        const val CPU_NAME_REGEX = "cpu[0-9]+"

        const val CPU_LOCATION = "/sys/devices/system/cpu/"

        /**
         * The default keep alive time for threads in source unlimited executor pool in milliseconds.
         */
        const val SOURCE_UNLIMITED_EXECUTOR_KEEP_ALIVE_TIME_MS = 10000L
        /**
         * Determines the number of cores available on the device.
         * <p>{@link Runtime#availableProcessors()} returns the number of awake cores, which may not
         * be the number of available cores depending on the device's current state. See
         * http://goo.gl/8H670N.
         */
        val CPU_COUNT:Int by lazy {
            // We override the current ThreadPolicy to allow disk reads.
            // This shouldn't actually do disk-IO and accesses a device file.
            // See: https://github.com/bumptech/glide/issues/1170
            var cpuList: Array<File>? = null
            var originalPolicy : StrictMode.ThreadPolicy? = null
            try {
                if (DEBUG){ //临时允许主线程磁盘读写
                    originalPolicy = StrictMode.allowThreadDiskReads()
                }
                val cpuInfo = File(CPU_LOCATION)
                val cpuNamePattern = Pattern.compile(CPU_NAME_REGEX)
                cpuList = cpuInfo.listFiles { dir, name -> cpuNamePattern.matcher(name).matches() }
            } catch (t: Throwable) {
                t.printStackTrace()
            } finally {
                if (DEBUG && originalPolicy != null){
                    StrictMode.setThreadPolicy(originalPolicy)
                }
            }
            val cpuCount = cpuList?.size ?: 0
            val availableProcessors = Math.max(1, Runtime.getRuntime().availableProcessors())
            Math.max(availableProcessors, cpuCount)
        }

        /**
         * Returns a new fixed thread pool with the default thread count returned from
         * {@link #calculateBestThreadCount()}, the {@link #DEFAULT_DISK_EXECUTOR_NAME} thread name
         * prefix, and the
         * uncaught throwable strategy.
         * <p>Disk cache executors do not allow network operations on their threads.
         */
        fun newDiskExecutor() = AppExecutor(2*CPU_COUNT+1,2*CPU_COUNT+1,DEFAULT_DISK_EXECUTOR_NAME,true)

        /**
         * Returns a new fixed thread pool with the default thread count returned from
         * {@link #calculateBestThreadCount()}, the {@link #DEFAULT_NETWORK_EXECUTOR_NAME} thread name
         * prefix, and the
         */
        fun newNetworkExecutor() = AppExecutor(CPU_COUNT+1,CPU_COUNT+1,DEFAULT_NETWORK_EXECUTOR_NAME,false)


        /**
         * Returns a new fixed thread pool with the given thread count, thread name prefix,
         * @param threadCount The number of threads.
         * @param name The prefix for each thread name.
         * @param preventNetworkOperations allow network request
         */
        fun newSourceExecutor(threadCount:Int,name:String,preventNetworkOperations:Boolean) = AppExecutor(threadCount,threadCount,name,preventNetworkOperations)

        /**
         * Returns a new unlimited thread pool with zero core thread count to make sure no threads are
         * created by default, {@link #SOURCE_UNLIMITED_EXECUTOR_KEEP_ALIVE_TIME_MS} keep alive
         * time, the {@link #SOURCE_UNLIMITED_EXECUTOR_NAME} thread name prefix, the
         * uncaught throwable strategy, and the {@link SynchronousQueue} since using default unbounded
         * blocking queue, for example, {@link PriorityBlockingQueue} effectively won't create more than
         * {@code corePoolSize} threads.
         * See <a href=
         * "http://developer.android.com/reference/java/util/concurrent/ThreadPoolExecutor.html">
         * ThreadPoolExecutor documentation</a>.
         * <p>Source executors allow network operations on their threads.
         */
        fun newUnlimitedSourceExecutor() = AppExecutor(0, Int.MAX_VALUE,SOURCE_UNLIMITED_EXECUTOR_KEEP_ALIVE_TIME_MS,SOURCE_UNLIMITED_EXECUTOR_NAME
                ,UncaughtThrowableStrategy.DEFAULT,false, SynchronousQueue<Runnable>())

    }

    /**
     * A strategy for handling unexpected and uncaught {@link Throwable}s thrown by futures run on the
     * pool.
     */
    enum class UncaughtThrowableStrategy{
        /**
         * Silently catches and ignores the uncaught {@link Throwable}s.
         */
        IGNORE,
        /**
         * Logs the uncaught {@link Throwable}s using {@link #TAG} and {@link Log}.
         */
        LOG{
            override fun handle(t:Throwable){
                t.printStackTrace()
                if (Log.isLoggable(TAG, Log.ERROR)){
                    Log.e(TAG, "Request threw uncaught throwable${t.message}")
                }
            }
        },
        /**
         * Rethrows the uncaught {@link Throwable}s to crash the app.
         */
        THROW{
            @Throws
            override fun handle(t:Throwable){
                t.printStackTrace()
                throw RuntimeException("Request threw uncaught throwable", t)
            }
        };

        companion object{
            /** The default strategy, currently {@link #LOG}. */
            val DEFAULT: UncaughtThrowableStrategy = LOG
        }

        open fun handle(t:Throwable){
            // Ignore.
        }
    }

    private class DefaultThreadFactory(nameParam:String, uncaughtParam: UncaughtThrowableStrategy, preventParam:Boolean) : ThreadFactory {

        private val name = nameParam

        private val uncaughtThrowableStrategy = uncaughtParam
        //是否阻止网络请求
        private val preventNetworkOperations = preventParam

        private var threadNum = AtomicInteger(0)

        override fun newThread(runnable: Runnable?) = object:Thread(runnable, "app-$name-thread-${threadNum.getAndIncrement()}"){
            override fun run() {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND+ Process.THREAD_PRIORITY_MORE_FAVORABLE)
                if (preventNetworkOperations && DEBUG){ //处理io线程池发送网络请求直接抛出异常,Debug模式使用
                    StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectNetwork().penaltyDeath().build())
                }
                try {
                    super.run()
                } catch (t: Throwable) {
                    uncaughtThrowableStrategy.handle(t)
                }
            }
        }
    }
}






