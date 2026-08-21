/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.fineract.portfolio.savings.jobs.postzerointerestpivots;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;

class PostZeroInterestPivotsForSavingsTaskletTest {

    @Test
    void rejectsWritesWhenPivotConfigurationIsNotStrict() {
        final ConfigurationDomainService configurationDomainService = mock(ConfigurationDomainService.class);
        final SavingsAccountRepository savingsAccountRepository = mock(SavingsAccountRepository.class);
        final PostZeroInterestPivotProcessor processor = mock(PostZeroInterestPivotProcessor.class);
        when(configurationDomainService.retrievePivotDateConfig()).thenReturn(false);
        final PostZeroInterestPivotsForSavingsTasklet tasklet = new PostZeroInterestPivotsForSavingsTasklet(
                configurationDomainService, savingsAccountRepository, processor);

        assertThatThrownBy(() -> tasklet.execute(mock(StepContribution.class), mock(ChunkContext.class)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("strict interest-posting pivot configuration");

        verifyNoInteractions(savingsAccountRepository, processor);
    }
}
