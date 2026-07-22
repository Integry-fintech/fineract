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
package org.apache.fineract.infrastructure.core.exceptionmapper;

import static org.apache.http.HttpStatus.SC_NOT_FOUND;

import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.apache.fineract.infrastructure.core.data.ApiGlobalErrorResponse;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class NotFoundExceptionMapperTest {

    @Test
    void testNotFoundExceptionMappedTo404() {
        NotFoundExceptionMapper notFoundExceptionMapper = new NotFoundExceptionMapper();
        Response response = notFoundExceptionMapper.toResponse(new NotFoundException("Not Found"));

        Assertions.assertEquals(1001, notFoundExceptionMapper.errorCode());
        Assertions.assertEquals(SC_NOT_FOUND, response.getStatus());
        Assertions.assertEquals(MediaType.APPLICATION_JSON, response.getMediaType().toString());
        Assertions.assertInstanceOf(ApiGlobalErrorResponse.class, response.getEntity());
    }
}
