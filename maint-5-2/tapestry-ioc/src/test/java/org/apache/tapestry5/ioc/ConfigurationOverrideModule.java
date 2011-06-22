// Copyright 2009 The Apache Software Foundation
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.apache.tapestry5.ioc;

public class ConfigurationOverrideModule
{
    public static void contributeOrderedNames(OrderedConfiguration<String> configuration)
    {
        configuration.add("wilma", "WILMA", "after:barney");
        configuration.override("fred", "Mr. Flintstone", "after:*");
    }

    public void contributeStringLookup(MappedConfiguration<String, String> configuration)
    {
        configuration.override("fred", "Mr. Flintstone");
        configuration.override("wilma", null);
    }
}
