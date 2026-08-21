/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.fineract.portfolio.savings.jobs.postzerointerestpivots;

import org.apache.fineract.infrastructure.jobs.service.JobName;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
public class PostZeroInterestPivotsForSavingsConfig {

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;

    public PostZeroInterestPivotsForSavingsConfig(final JobRepository jobRepository,
            final PlatformTransactionManager transactionManager) {
        this.jobRepository = jobRepository;
        this.transactionManager = transactionManager;
    }

    @Bean
    protected Step postZeroInterestPivotsForSavingsStep(final PostZeroInterestPivotsForSavingsTasklet tasklet) {
        return new StepBuilder(JobName.POST_ZERO_INTEREST_PIVOTS_FOR_SAVINGS.name(), this.jobRepository)
                .tasklet(tasklet, this.transactionManager).build();
    }

    @Bean
    public Job postZeroInterestPivotsForSavingsJob(final PostZeroInterestPivotsForSavingsTasklet tasklet) {
        return new JobBuilder(JobName.POST_ZERO_INTEREST_PIVOTS_FOR_SAVINGS.name(), this.jobRepository)
                .start(postZeroInterestPivotsForSavingsStep(tasklet)).incrementer(new RunIdIncrementer()).build();
    }
}
