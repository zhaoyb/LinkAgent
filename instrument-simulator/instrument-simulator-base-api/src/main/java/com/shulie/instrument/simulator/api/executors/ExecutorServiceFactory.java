/**
 * Copyright 2021 Shulie Technology, Co.Ltd
 * Email: shulie@shulie.io
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.shulie.instrument.simulator.api.executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * 全局的线程工厂类，所有的扩展线程组都使用该工厂的线程组
 *
 * @author xiaobin.zfb|xiaobin@shulie.io
 * @since 2021/1/9 3:20 下午
 */
public class ExecutorServiceFactory {
    private final Logger LOGGER = LoggerFactory.getLogger(ExecutorServiceFactory.class);
    /**
     * 全局的定时执行线程池
     */
    private ScheduledExecutorService globalScheduleExecutorService;

    /**
     * 全局的执行线程池
     */
    private ExecutorService globalExecutorService;

    private ConcurrentHashMap<Task, TaskInfo> tasks;

    private static ExecutorServiceFactory instance;

    public static ExecutorServiceFactory getFactory() {
        if (instance == null) {
            synchronized (ExecutorServiceFactory.class) {
                if (instance == null) {
                    instance = new ExecutorServiceFactory();
                }
            }
        }
        return instance;
    }

    public ScheduledExecutorService getGlobalScheduleExecutorService() {
        return globalScheduleExecutorService;
    }

    public ExecutorService getGlobalExecutorService() {
        return globalExecutorService;
    }

    public ScheduledFuture<?> schedule(Runnable command,
                                       long delay, TimeUnit unit) {
        if (globalScheduleExecutorService == null) {
            return null;
        }
        return globalScheduleExecutorService.schedule(command, delay, unit);
    }


    public <V> ScheduledFuture<V> schedule(Callable<V> callable,
                                           long delay, TimeUnit unit) {
        if (globalScheduleExecutorService == null) {
            return null;
        }
        return globalScheduleExecutorService.schedule(callable, delay, unit);
    }


    public ScheduledFuture<?> scheduleAtFixedRate(Runnable command,
                                                  long initialDelay,
                                                  long period,
                                                  TimeUnit unit) {
        if (globalScheduleExecutorService == null) {
            return null;
        }
        return globalScheduleExecutorService.scheduleAtFixedRate(command, initialDelay, period, unit);
    }

    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command,
                                                     long initialDelay,
                                                     long delay,
                                                     TimeUnit unit) {
        if (globalScheduleExecutorService == null) {
            return null;
        }
        return globalScheduleExecutorService.scheduleAtFixedRate(command, initialDelay, delay, unit);
    }

    public <T> Future<T> submit(Callable<T> task) {
        if (globalExecutorService == null) {
            return null;
        }
        return globalExecutorService.submit(task);
    }

    public <T> Future<T> submit(Runnable task, T result) {
        if (globalExecutorService == null) {
            return null;
        }
        return globalExecutorService.submit(task, result);
    }

    public Future<?> submit(Runnable task) {
        if (globalExecutorService == null) {
            return null;
        }
        return globalExecutorService.submit(task);
    }

    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks)
            throws InterruptedException {
        if (globalExecutorService == null) {
            return null;
        }
        return globalExecutorService.invokeAll(tasks);
    }

    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks,
                                         long timeout, TimeUnit unit)
            throws InterruptedException {
        if (globalExecutorService == null) {
            return null;
        }
        return globalExecutorService.invokeAll(tasks, timeout, unit);
    }

    public <T> T invokeAny(Collection<? extends Callable<T>> tasks)
            throws InterruptedException, ExecutionException {
        if (globalExecutorService == null) {
            return null;
        }
        return globalExecutorService.invokeAny(tasks);
    }

    public <T> T invokeAny(Collection<? extends Callable<T>> tasks,
                           long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        if (globalExecutorService == null) {
            return null;
        }
        return globalExecutorService.invokeAny(tasks, timeout, unit);
    }

    public void execute(Runnable command) {
        if (globalExecutorService == null) {
            return;
        }
        globalExecutorService.execute(command);
    }

    public ExecutorServiceFactory() {
        this.globalScheduleExecutorService = getScheduledExecutorService();
        this.globalExecutorService = getExecutorService();
        this.tasks = new ConcurrentHashMap<Task, TaskInfo>();
    }

    /**
     * shutdown executor service factory
     */
    public void shutdown() {
        if (this.globalExecutorService != null) {
            this.globalExecutorService.shutdownNow();
        }
        if (this.globalScheduleExecutorService != null) {
            this.globalScheduleExecutorService.shutdownNow();
        }
        this.tasks.clear();
    }

    /**
     * 判断任务是否还在运行中
     *
     * @param taskInfo
     * @return
     */
    private boolean isRunning(TaskInfo taskInfo) {
        if (taskInfo == null) {
            return true;
        }
        return taskInfo.running;
    }

    /**
     * 可靠执行任务，因为执行任务有可能会失败
     * 当任务执行失败时进行重试队列，可指定重试时间间隔
     *
     * @param task
     */
    public void reliableExecute(final Task task, final int retryIntervalSec) {
        try {
            boolean success = task.execute();
            if (!success) {
                ScheduledFuture future = globalScheduleExecutorService.schedule(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            boolean success = task.execute();
                            TaskInfo taskInfo = tasks.get(task);
                            if (!success && isRunning(taskInfo)) {
                                taskInfo.failed();
                                if (task.getMaxRetryTimes() >= 0 && taskInfo.getFailedCount() <= task.getMaxRetryTimes()) {
                                    ScheduledFuture f = globalScheduleExecutorService.schedule(this, retryIntervalSec, TimeUnit.MILLISECONDS);
                                    taskInfo = tasks.get(task);
                                    if (taskInfo == null) {
                                        taskInfo = new TaskInfo(f);
                                        TaskInfo oldTask = tasks.putIfAbsent(task, taskInfo);
                                        if (oldTask != null) {
                                            oldTask.setScheduledFuture(f);
                                        }
                                    } else {
                                        taskInfo.setScheduledFuture(f);
                                    }
                                } else {
                                    tasks.remove(task);
                                    task.onFailed();
                                }
                            } else {
                                tasks.remove(task);
                            }
                        } catch (Exception e) {
                            TaskInfo taskInfo = tasks.get(task);
                            taskInfo.failed();
                            if (isRunning(taskInfo)) {
                                if (task.getMaxRetryTimes() >= 0 && taskInfo.getFailedCount() <= task.getMaxRetryTimes()) {
                                    ScheduledFuture f = globalScheduleExecutorService.schedule(this, retryIntervalSec, TimeUnit.MILLISECONDS);
                                    taskInfo = tasks.get(task);
                                    if (taskInfo == null) {
                                        taskInfo = new TaskInfo(f);
                                        TaskInfo oldTask = tasks.putIfAbsent(task, taskInfo);
                                        if (oldTask != null) {
                                            oldTask.setScheduledFuture(f);
                                        }
                                    } else {
                                        taskInfo.setScheduledFuture(f);
                                    }
                                } else {
                                    tasks.remove(task);
                                    task.onFailed();
                                }
                            } else {
                                tasks.remove(task);
                            }
                        }
                    }
                }, retryIntervalSec, TimeUnit.MILLISECONDS);
                TaskInfo taskInfo = tasks.get(task);
                if (taskInfo == null) {
                    taskInfo = new TaskInfo(future);
                    TaskInfo oldTask = tasks.putIfAbsent(task, taskInfo);
                    if (oldTask != null) {
                        oldTask.setScheduledFuture(future);
                    }
                } else {
                    taskInfo.setScheduledFuture(future);
                }
            }
        } catch (Throwable e) {
            ScheduledFuture future = globalScheduleExecutorService.schedule(new Runnable() {
                @Override
                public void run() {
                    try {
                        boolean success = task.execute();
                        TaskInfo taskInfo = tasks.get(task);
                        if (!success && isRunning(taskInfo)) {
                            taskInfo.failed();
                            if (task.getMaxRetryTimes() >= 0 && taskInfo.getFailedCount() <= task.getMaxRetryTimes()) {
                                ScheduledFuture f = globalScheduleExecutorService.schedule(this, retryIntervalSec, TimeUnit.MILLISECONDS);
                                taskInfo = tasks.get(task);
                                if (taskInfo == null) {
                                    taskInfo = new TaskInfo(f);
                                    TaskInfo oldTask = tasks.putIfAbsent(task, taskInfo);
                                    if (oldTask != null) {
                                        oldTask.setScheduledFuture(f);
                                    }
                                } else {
                                    taskInfo.setScheduledFuture(f);
                                }
                            } else {
                                tasks.remove(task);
                                task.onFailed();
                            }
                        } else {
                            tasks.remove(task);
                        }
                    } catch (Exception exception) {
                        TaskInfo taskInfo = tasks.get(task);
                        taskInfo.failed();
                        if (isRunning(taskInfo)) {
                            if (task.getMaxRetryTimes() >= 0 && taskInfo.getFailedCount() <= task.getMaxRetryTimes()) {
                                ScheduledFuture f = globalScheduleExecutorService.schedule(this, retryIntervalSec, TimeUnit.MILLISECONDS);
                                taskInfo = tasks.get(task);
                                if (taskInfo == null) {
                                    taskInfo = new TaskInfo(f);
                                    TaskInfo oldTask = tasks.putIfAbsent(task, taskInfo);
                                    if (oldTask != null) {
                                        oldTask.setScheduledFuture(f);
                                    }
                                } else {
                                    taskInfo.setScheduledFuture(f);
                                }
                            } else {
                                tasks.remove(task);
                                task.onFailed();
                            }
                        } else {
                            tasks.remove(task);
                        }
                    }
                }
            }, retryIntervalSec, TimeUnit.MILLISECONDS);
            TaskInfo taskInfo = tasks.get(task);
            if (taskInfo == null) {
                taskInfo = new TaskInfo(future);
                TaskInfo oldTask = tasks.putIfAbsent(task, taskInfo);
                if (oldTask != null) {
                    oldTask.setScheduledFuture(future);
                }
            } else {
                taskInfo.setScheduledFuture(future);
            }
        }
    }

    /**
     * 可靠执行任务，因为执行任务有可能会失败
     * 当任务执行失败时进行重试队列，默认重试时间间隔为1000ms
     *
     * @param task
     */
    public void reliableExecute(Task task) {
        reliableExecute(task, 1000);
    }

    private boolean equals(String str1, String str2) {
        if (str1 == str2) {
            return true;
        }
        if (str1 == null || str2 == null) {
            return false;
        }
        return str1.equals(str2);
    }

    /**
     * 当使用了可靠执行后，当任务一直执行失败，则任务会不断重试，可以通过调用
     * 此方法来中断方法的重试
     *
     * @param taskGroup
     */
    public void cancelTask(String taskGroup) {
        Iterator<Map.Entry<Task, TaskInfo>> it = tasks.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Task, TaskInfo> entry = it.next();
            if (equals(entry.getKey().getTaskGroup(), taskGroup)) {
                try {
                    TaskInfo taskInfo = entry.getValue();
                    if (taskInfo == null) {
                        return;
                    }
                    taskInfo.setRunning(false);
                    ScheduledFuture future = taskInfo.scheduledFuture;
                    if (future != null && !future.isCancelled() && !future.isDone()) {
                        future.cancel(true);
                    }
                } finally {
                    it.remove();
                }
            }
        }

    }

    /**
     * 获取定时调度的线程组
     *
     * @return
     */
    private ScheduledExecutorService getScheduledExecutorService() {
        int processors = Runtime.getRuntime().availableProcessors();
        if (processors <= 0) {
            processors = 4;
        }
        return new ScheduledThreadPoolExecutor(processors, new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread t = new Thread(runnable, "Simulator-Pool-Schedule-Executor-Service");
                t.setDaemon(true);
                t.setUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                    @Override
                    public void uncaughtException(Thread t, Throwable e) {
                        if (e instanceof InterruptedException) {
                            t.interrupt();
                        }
                        LOGGER.error("Thread {} caught a unknow exception with UncaughtExceptionHandler", t.getName(), e);
                    }
                });
                return t;
            }
        });
    }

    /**
     * 获取执行线程组
     *
     * @return
     */
    private ExecutorService getExecutorService() {
        int processors = Runtime.getRuntime().availableProcessors();
        if (processors <= 0) {
            processors = 4;
        }

        return new ThreadPoolExecutor(processors, processors * 2, 1000, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(1000), new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread t = new Thread(runnable, "Simulator-Pool-Executor-Service");
                t.setDaemon(true);
                t.setUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                    @Override
                    public void uncaughtException(Thread t, Throwable e) {
                        if (e instanceof InterruptedException) {
                            t.interrupt();
                        }
                        LOGGER.error("Thread {} caught a unknow exception with UncaughtExceptionHandler", t.getName(), e);
                    }
                });
                return t;
            }
        });
    }

    private class TaskInfo {
        private boolean running = true;
        private int failedCount;
        private ScheduledFuture scheduledFuture;

        public TaskInfo(ScheduledFuture scheduledFuture) {
            this.scheduledFuture = scheduledFuture;
        }

        public int getFailedCount() {
            return failedCount;
        }

        public void setFailedCount(int failedCount) {
            this.failedCount = failedCount;
        }

        public void failed() {
            this.failedCount++;
        }

        public boolean isRunning() {
            return running;
        }

        public void setRunning(boolean running) {
            this.running = running;
        }

        public ScheduledFuture getScheduledFuture() {
            return scheduledFuture;
        }

        public void setScheduledFuture(ScheduledFuture scheduledFuture) {
            this.scheduledFuture = scheduledFuture;
        }
    }

    /**
     * 任务
     */
    public interface Task {
        /**
         * 获取最大重试试数，如果为-1，则永远重试
         *
         * @return
         */
        int getMaxRetryTimes();

        /**
         * 失败回调函数
         */
        void onFailed();

        /**
         * 获取任务组
         *
         * @return
         */
        String getTaskGroup();

        /**
         * 执行任务
         *
         * @return
         */
        boolean execute();
    }
}
