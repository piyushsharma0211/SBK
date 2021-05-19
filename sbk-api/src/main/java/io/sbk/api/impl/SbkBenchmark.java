/**
 * Copyright (c) KMG. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.sbk.api.impl;

import io.sbk.api.Action;
import io.sbk.api.Benchmark;
import io.sbk.api.Config;
import io.sbk.api.DataReader;
import io.sbk.api.DataType;
import io.sbk.api.DataWriter;
import io.sbk.api.Parameters;
import io.sbk.api.Logger;
import io.sbk.perl.Performance;
import io.sbk.perl.PerlConfig;
import io.sbk.perl.PeriodicLatencyRecorder;
import io.sbk.api.Storage;
import io.sbk.perl.Time;
import io.sbk.perl.impl.ArrayLatencyRecorder;
import io.sbk.perl.impl.CompositeCSVLatencyRecorder;
import io.sbk.perl.impl.CompositeHashMapLatencyRecorder;
import io.sbk.perl.impl.HashMapLatencyRecorder;
import io.sbk.perl.impl.LatencyWindow;
import io.sbk.perl.impl.CQueuePerformance;
import io.sbk.system.Printer;
import lombok.Synchronized;

import javax.annotation.concurrent.GuardedBy;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * Class for performing the benchmark.
 */
public class SbkBenchmark implements Benchmark {
    final private String storageName;
    final private Action action;
    final private PerlConfig perlConfig;
    final private Storage<Object> storage;
    final private DataType<Object> dType;
    final private Time time;
    final private Logger logger;
    final private ExecutorService executor;
    final private Parameters params;
    final private Performance writeStats;
    final private Performance readStats;
    final private int maxQs;
    final private double[] percentileFractions;
    final private ScheduledExecutorService timeoutExecutor;
    private List<DataWriter<Object>> writers;
    private List<DataReader<Object>> readers;

    @GuardedBy("this")
    private CompletableFuture<Void> retFuture;

    /**
     * Create SBK Benchmark.
     *
     * @param  storageName          Storage Name
     * @param  action               Action
     * @param  perlConfig               Configuration parameters
     * @param  params               Benchmarking input Parameters
     * @param  storage              Storage device/client/driver for benchmarking
     * @param  dType                Data Type.
     * @param  logger               output logger
     * @param  time                 time interface
     * @throws IOException          If Exception occurs.
     */
    public SbkBenchmark(String storageName, Action action, PerlConfig perlConfig,
                        Parameters params, Storage<Object> storage,
                        DataType<Object> dType, Logger logger, Time time) throws IOException {
        this.storageName = storageName;
        this.dType = dType;
        this.action = action;
        this.perlConfig = perlConfig;
        this.params = params;
        this.storage = storage;
        this.logger = logger;
        this.time = time;
        final double[] percentiles = logger.getPercentiles();
        percentileFractions = new double[percentiles.length];

        for (int i = 0; i < percentiles.length; i++) {
            percentileFractions[i] = percentiles[i] / 100.0;
        }

        if (perlConfig.maxQs > 0) {
            this.maxQs = perlConfig.maxQs;
        } else {
            this.maxQs = Math.max(PerlConfig.MIN_Q_PER_WORKER, perlConfig.qPerWorker);
        }

        final int threadCount = params.getWritersCount() + params.getReadersCount() + 10;
        if (Config.USE_FORK) {
            executor = new ForkJoinPool(threadCount);
        } else {
            executor = Executors.newFixedThreadPool(threadCount);
        }
        if (params.getWritersCount() > 0 && !params.isWriteAndRead()) {
            writeStats = new CQueuePerformance(perlConfig, params.getWritersCount(), createLatencyRecorder(),
                    logger.getReportingIntervalSeconds() * PerlConfig.MS_PER_SEC, this.time, executor);
        } else {
            writeStats = null;
        }

        if (params.getReadersCount() > 0) {
            readStats = new CQueuePerformance(perlConfig, params.getReadersCount(), createLatencyRecorder(),
                    logger.getReportingIntervalSeconds() * PerlConfig.MS_PER_SEC, this.time, executor);
        } else {
            readStats = null;
        }
        timeoutExecutor = Executors.newScheduledThreadPool(1);
        retFuture = null;
    }


    private PeriodicLatencyRecorder createLatencyRecorder() {
        final long latencyRange = logger.getMaxLatency() - logger.getMinLatency();
        final long memSizeMB = (latencyRange * PerlConfig.LATENCY_VALUE_SIZE_BYTES) / (1024 * 1024);
        final LatencyWindow window;
        final PeriodicLatencyRecorder latencyRecorder;

        if (memSizeMB < perlConfig.maxArraySizeMB && latencyRange < Integer.MAX_VALUE) {
            window = new ArrayLatencyRecorder(logger.getMinLatency(), logger.getMaxLatency(),
                    PerlConfig.LONG_MAX, PerlConfig.LONG_MAX, PerlConfig.LONG_MAX, percentileFractions, time);
            Printer.log.info("Window Latency Store: Array");
        } else {
            window = new HashMapLatencyRecorder(logger.getMinLatency(), logger.getMaxLatency(),
                    PerlConfig.LONG_MAX, PerlConfig.LONG_MAX, PerlConfig.LONG_MAX, percentileFractions, time, perlConfig.maxHashMapSizeMB);
            Printer.log.info("Window Latency Store: HashMap");

        }
        if (perlConfig.csv) {
            latencyRecorder = new CompositeCSVLatencyRecorder(window, perlConfig.maxHashMapSizeMB, logger, logger::printTotal,
                    Config.NAME + "-" + String.format("%06d", new Random().nextInt(1000000)) + ".csv" );
            Printer.log.info("Total Window Latency Store: HashMap and CSV file");
        } else {
            latencyRecorder = new CompositeHashMapLatencyRecorder(window, perlConfig.maxHashMapSizeMB, logger, logger::printTotal);
            Printer.log.info("Total Window Latency Store: HashMap");
        }
        return latencyRecorder;
    }

    /**
     * Start SBK Benchmark.
     *
     * opens the storage device/client , creates the writers/readers.
     * conducts the performance benchmarking for given time in seconds
     * or exits if the input the number of records are written/read.
     * NOTE: This method does NOT invoke parsing of parameters, storage device/client.
     *
     * @throws IOException If an exception occurred.
     * @throws IllegalStateException If an exception occurred.
     */
    @Override
    @Synchronized
    public CompletableFuture<Void> start() throws IOException, InterruptedException, ExecutionException,
            IllegalStateException {
        if (retFuture != null) {
            throw  new IllegalStateException("SbkBenchmark is already started\n");
        }
        Printer.log.info("SBK Benchmark Started");
        logger.open(params, storageName, action, time);
        storage.openStorage(params);
        final List<SbkWriter> sbkWriters;
        final List<SbkReader> sbkReaders;
        final List<CompletableFuture<Void>> writeFutures;
        final List<CompletableFuture<Void>> readFutures;
        final CompletableFuture<Void> wStatFuture;
        final CompletableFuture<Void> rStatFuture;
        final CompletableFuture<Void> chainFuture;
        final CompletableFuture<Void> writersCB;
        final CompletableFuture<Void> readersCB;

        writers = IntStream.range(0, params.getWritersCount())
                .boxed()
                .map(i -> storage.createWriter(i, params))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        readers = IntStream.range(0, params.getReadersCount())
                .boxed()
                .map(i -> storage.createReader(i, params))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        if (writers != null && writers.size() > 0) {
            if (writeStats != null) {
                sbkWriters = IntStream.range(0, params.getWritersCount())
                        .boxed()
                        .map(i -> new SbkWriter(i, maxQs, params, writeStats.getSendChannel(),
                                dType, time, writers.get(i)))
                        .collect(Collectors.toList());
            } else {
                sbkWriters = IntStream.range(0, params.getWritersCount())
                        .boxed()
                        .map(i -> new SbkWriter(i, maxQs,  params, null,
                                dType, time, writers.get(i)))
                        .collect(Collectors.toList());
            }
        } else {
            sbkWriters = null;
        }

        if (readers != null && readers.size() > 0) {
            sbkReaders = IntStream.range(0, params.getReadersCount())
                    .boxed()
                    .map(i -> new SbkReader(i, maxQs, params,
                            readStats.getSendChannel(), dType, time, readers.get(i)))
                    .collect(Collectors.toList());
        }  else {
            sbkReaders = null;
        }

        if (writeStats != null && !params.isWriteAndRead() && sbkWriters != null) {
            wStatFuture = writeStats.run(params.getTotalSecondsToRun(), params.getTotalRecords());
        } else {
            wStatFuture = null;
        }
        if (readStats != null && sbkReaders != null) {
            rStatFuture = readStats.run(params.getTotalSecondsToRun(), params.getTotalRecords());
        } else {
            rStatFuture = null;
        }
        if (sbkWriters != null) {
            writeFutures = new ArrayList<>();

            final long recordsPerWriter = params.getTotalSecondsToRun() <= 0 ?
                    params.getTotalRecords() / params.getWritersCount() : 0;
            final long delta = recordsPerWriter > 0 ?
                    params.getTotalRecords() - (recordsPerWriter * params.getWritersCount()) : 0;

            writersCB = CompletableFuture.runAsync( () -> {
                long secondsToRun = params.getTotalSecondsToRun();
                int i = 0;
                while (i < params.getWritersCount()) {
                    final int stepCnt = Math.min(params.getWritersStep(), params.getWritersCount() - i);
                    logger.incrementWriters(stepCnt);
                    for (int j = 0; j < stepCnt; j++) {
                        try {
                            CompletableFuture<Void> ret = sbkWriters.get(i + j).run(secondsToRun,
                                    i + j + 1 == params.getWritersCount() ?
                                    recordsPerWriter + delta : recordsPerWriter);
                            ret.exceptionally(ex -> {
                                logger.decrementWriters(1);
                                return null;
                            });
                            ret.thenAccept(d -> {
                                logger.decrementWriters(1);
                            });
                            writeFutures.add(ret);
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                    i += params.getWritersStep();
                    if (params.getWritersStepSeconds() > 0 && i < params.getWritersCount()) {
                        try {
                            Thread.sleep((long) params.getWritersStepSeconds() * PerlConfig.MS_PER_SEC);
                            secondsToRun -= params.getWritersStepSeconds();
                        } catch (InterruptedException ex) {
                            ex.printStackTrace();
                        }
                    }
                }
            }, executor);
            Printer.log.info("SBK Benchmark initiated Writers");

        } else {
            writersCB = null;
            writeFutures = null;
        }

        if (sbkReaders != null) {
            readFutures = new ArrayList<>();

            final long recordsPerReader = params.getTotalSecondsToRun() <= 0 ?
                    params.getTotalRecords() / params.getReadersCount() : 0;
            final long delta = recordsPerReader > 0 ?
                    params.getTotalRecords() - (recordsPerReader * params.getReadersCount()) : 0;

            readersCB = CompletableFuture.runAsync(() -> {
                long secondsToRun = params.getTotalSecondsToRun();
                int i = 0;
                while (i < params.getReadersCount())  {
                    int stepCnt = Math.min(params.getReadersStep(), params.getReadersCount()-i);
                    logger.incrementReaders(stepCnt);
                    for (int j = 0; j < Math.min(params.getReadersStep(), params.getReadersCount()-i); j++) {
                        try {
                            CompletableFuture<Void> ret = sbkReaders.get(i+j).run(secondsToRun, i+j+1 == params.getReadersCount() ?
                                    recordsPerReader + delta : recordsPerReader);
                            ret.exceptionally(ex -> {
                                logger.decrementReaders(1);
                                return null;
                            });
                            ret.thenAccept(d -> {
                                logger.decrementReaders(1);
                            });
                            readFutures.add(ret);
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                    i += params.getReadersStep();
                    if (params.getReadersStepSeconds() > 0 && i < params.getReadersCount()) {
                        try {
                            Thread.sleep((long) params.getReadersStepSeconds() * PerlConfig.MS_PER_SEC);
                            secondsToRun -=  params.getReadersStepSeconds();
                        } catch (InterruptedException ex) {
                            ex.printStackTrace();
                        }
                    }
                }
            }, executor);
            Printer.log.info("SBK Benchmark initiated Readers");
        } else {
            readersCB = null;
            readFutures = null;
        }

        if (writersCB != null) {
            writersCB.get();
        }

        if (readersCB != null) {
            readersCB.get();
        }

        if (writeFutures != null && readFutures != null) {
            chainFuture = CompletableFuture.allOf(Stream.concat(writeFutures.stream(), readFutures.stream()).
                    collect(Collectors.toList()).toArray(new CompletableFuture[writeFutures.size() + readFutures.size()]));
        } else if (readFutures != null) {
            chainFuture = CompletableFuture.allOf(readFutures.toArray(new CompletableFuture[0]));
        } else if (writeFutures != null) {
            chainFuture = CompletableFuture.allOf(writeFutures.toArray(new CompletableFuture[0]));
        } else {
            throw new IllegalStateException("No Writers and/or Readers\n");
        }

        if (params.getTotalSecondsToRun() > 0) {
            timeoutExecutor.schedule(this::stop, params.getTotalSecondsToRun() + 1, TimeUnit.SECONDS);
        }

        if (wStatFuture != null && !wStatFuture.isDone()) {
            wStatFuture.exceptionally(ex -> {
                shutdown(ex);
                return null;
            });
        }

        if (rStatFuture != null && !rStatFuture.isDone()) {
            rStatFuture.exceptionally(ex -> {
                shutdown(ex);
                return null;
            });
        }

        retFuture = chainFuture.thenRunAsync(this::stop, executor);

        return retFuture;
    }

    /**
     * Shutdown SBK Benchmark.
     *
     * closes all writers/readers.
     * closes the storage device/client.
     *
     */
    @Synchronized
    private void shutdown(Throwable ex) {
        if (retFuture == null) {
            return;
        }

        if (retFuture.isDone()) {
            retFuture = null;
            return;
        }

        if (writeStats != null ) {
            writeStats.stop();
        }
        if (readStats != null) {
            readStats.stop();
        }
        if (readers != null) {
            readers.forEach(c -> {
                try {
                    c.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            });
        }
        if (writers != null) {
            writers.forEach(c -> {
                try {
                    c.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            });
        }
        try {
            storage.closeStorage(params);
            logger.close(params);
        } catch (IOException e) {
            e.printStackTrace();
        }
        executor.shutdown();
        try {
            executor.awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            ex.printStackTrace();
        }

        if (ex != null) {
            Printer.log.warn("SBK Benchmark Shutdown with Exception "+ex.toString());
            retFuture.completeExceptionally(ex);
        } else {
            Printer.log.info("SBK Benchmark Shutdown");
            retFuture.complete(null);
        }
        retFuture = null;
    }


    /**
     * Stop/shutdown SBK Benchmark.
     *
     * closes all writers/readers.
     * closes the storage device/client.
     *
     */
    @Override
    public void stop() {
        shutdown(null);
    }
}
