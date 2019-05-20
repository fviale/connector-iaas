/*
 * ProActive Parallel Suite(TM):
 * The Open Source library for parallel and distributed
 * Workflows & Scheduling, Orchestration, Cloud Automation
 * and Big Data Analysis on Enterprise Grids & Clouds.
 *
 * Copyright (c) 2007 - 2017 ActiveEon
 * Contact: contact@activeeon.com
 *
 * This library is free software: you can redistribute it and/or
 * modify it under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation: version 3 of
 * the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 *
 * If needed, contact us to obtain a release under GPL Version 2 or 3
 * or a different license than the AGPL.
 */
package org.ow2.proactive.connector.iaas.cloud;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.ow2.proactive.connector.iaas.model.Options;
import org.ow2.proactive.connector.iaas.model.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import lombok.Getter;


@Component
public class TagManager {

    private static final String DEFAULT_CONNECTOR_IAAS_TAG_KEY = "proactive-connector-iaas";

    private static final String DEFAULT_CONNECTOR_IAAS_TAG_VALUE = "default-tag";

    private static final String DEFAULT_INFRASTRUCTURE_ID_TAG_KEY = "proactive-infrastructure-id";

    @Getter
    private final Tag connectorIaasTag;

    @Getter
    private final Tag infrastructureIdTag;

    @Autowired
    public TagManager(@Value("${connector-iaas-tag.key}") String connectorIaasTagKey,
            @Value("${connector-iaas-tag.value}") String connectorIaasTagValue,
            @Value("${infrastructure-id-tag.key}") String infrastructureIdTagKey) {
        connectorIaasTag = Tag.builder()
                              .key(Optional.ofNullable(connectorIaasTagKey).orElse(DEFAULT_CONNECTOR_IAAS_TAG_KEY))
                              .value(Optional.ofNullable(connectorIaasTagValue)
                                             .orElse(DEFAULT_CONNECTOR_IAAS_TAG_VALUE))
                              .build();
        infrastructureIdTag = Tag.builder()
                                 .key(Optional.ofNullable(infrastructureIdTagKey)
                                              .orElse(DEFAULT_INFRASTRUCTURE_ID_TAG_KEY))
                                 .build();
    }

    /**
     * Collect tags and ensure that mandatory tags key connector-iaas and infrastructure-id are not duplicated
     *
     * @param instanceOptions   instance's options that may contain tags
     * @return  the list of all tags
     */
    public List<Tag> retrieveAllTags(String infrastructureId, Options instanceOptions) {
        infrastructureIdTag.setValue(infrastructureId);
        List<Tag> tags = new ArrayList<>();
        tags.add(connectorIaasTag);
        tags.add(infrastructureIdTag);
        Optional.ofNullable(instanceOptions).map(Options::getTags).ifPresent(optionalTags -> {
            tags.addAll(optionalTags.stream()
                                    .filter(optionalTag -> !optionalTag.getKey().equals(connectorIaasTag.getKey()))
                                    .filter(optionalTag -> !optionalTag.getKey().equals(infrastructureIdTag.getKey()))
                                    .collect(Collectors.toList()));
        });
        return tags;
    }
}
