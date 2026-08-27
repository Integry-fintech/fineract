/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.fineract.portfolio.savings.jobs.postzerointerestpivots;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.configuration.domain.ConfigurationDomainService;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.scope.context.StepContext;

class PostZeroInterestPivotsForSavingsTaskletTest {

    @BeforeEach
    void setUp() {
        ThreadLocalContextUtil.setBusinessDates(new HashMap<>(Map.of(BusinessDateType.BUSINESS_DATE, LocalDate.of(2026, 8, 13))));
    }

    @AfterEach
    void tearDown() {
        ThreadLocalContextUtil.reset();
    }

    @Test
    void rejectsWritesWhenPivotConfigurationIsNotStrict() {
        final ConfigurationDomainService configurationDomainService = mock(ConfigurationDomainService.class);
        final SavingsAccountRepository savingsAccountRepository = mock(SavingsAccountRepository.class);
        final PostZeroInterestPivotProcessor processor = mock(PostZeroInterestPivotProcessor.class);
        when(configurationDomainService.retrievePivotDateConfig()).thenReturn(false);
        final PostZeroInterestPivotsForSavingsTasklet tasklet = new PostZeroInterestPivotsForSavingsTasklet(configurationDomainService,
                savingsAccountRepository, processor);

        assertThatThrownBy(() -> tasklet.execute(mock(StepContribution.class), mock(ChunkContext.class)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("strict interest-posting pivot configuration");

        verifyNoInteractions(savingsAccountRepository, processor);
    }

    @Test
    void stopsProcessingWhenAnAccountFails() {
        final ConfigurationDomainService configurationDomainService = mock(ConfigurationDomainService.class);
        final SavingsAccountRepository savingsAccountRepository = mock(SavingsAccountRepository.class);
        final PostZeroInterestPivotProcessor processor = mock(PostZeroInterestPivotProcessor.class);
        final ChunkContext chunkContext = mock(ChunkContext.class);
        final StepContext stepContext = mock(StepContext.class);
        when(configurationDomainService.retrievePivotDateConfig()).thenReturn(true);
        when(chunkContext.getStepContext()).thenReturn(stepContext);
        when(stepContext.getJobParameters()).thenReturn(Map.of("thread-pool-size", "1", "batch-size", "3"));
        when(savingsAccountRepository.findZeroInterestPivotCandidateIds(anyLong(), any(), any(), any())).thenReturn(List.of(1L, 2L, 3L));
        when(processor.postPivot(eq(1L), any(LocalDate.class))).thenReturn(true);
        when(processor.postPivot(eq(2L), any(LocalDate.class))).thenThrow(new IllegalStateException("account failed"));
        final PostZeroInterestPivotsForSavingsTasklet tasklet = new PostZeroInterestPivotsForSavingsTasklet(configurationDomainService,
                savingsAccountRepository, processor);

        assertThatThrownBy(() -> tasklet.execute(mock(StepContribution.class), chunkContext)).isInstanceOf(IllegalStateException.class)
                .hasMessage("account failed");

        verify(processor).postPivot(eq(1L), any(LocalDate.class));
        verify(processor).postPivot(eq(2L), any(LocalDate.class));
        verify(processor, never()).postPivot(eq(3L), any(LocalDate.class));
    }
}
