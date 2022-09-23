/*
 * Copyright [2022] [DMetaSoul Team]
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.dmetasoul.lakesoul.meta.external;

import com.dmetasoul.lakesoul.meta.entity.Namespace;

import java.util.*;

public interface ExternalDBManager {

    List<String> listTables();

//    List<Namespace> listNamespaces();
//
//    DatabaseSchemaedTables getDatabaseAndTablesWithSchema();

    void importOrSyncLakeSoulTable(String tableName);

    void importOrSyncLakeSoulNamespace(String namespace);
}
