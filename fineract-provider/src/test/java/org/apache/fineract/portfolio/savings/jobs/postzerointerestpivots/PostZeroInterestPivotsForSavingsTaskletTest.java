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
