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
package org.ow2.proactive.connector.iaas.cloud.provider.azure;

import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.ow2.proactive.connector.iaas.cloud.TagManager;
import org.ow2.proactive.connector.iaas.cloud.provider.CloudProvider;
import org.ow2.proactive.connector.iaas.model.Hardware;
import org.ow2.proactive.connector.iaas.model.Image;
import org.ow2.proactive.connector.iaas.model.Infrastructure;
import org.ow2.proactive.connector.iaas.model.Instance;
import org.ow2.proactive.connector.iaas.model.InstanceCredentials;
import org.ow2.proactive.connector.iaas.model.InstanceScript;
import org.ow2.proactive.connector.iaas.model.Options;
import org.ow2.proactive.connector.iaas.model.ScriptResult;
import org.ow2.proactive.connector.iaas.model.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.google.common.collect.Lists;
import com.microsoft.azure.management.Azure;
import com.microsoft.azure.management.compute.OperatingSystemTypes;
import com.microsoft.azure.management.compute.VirtualMachine;
import com.microsoft.azure.management.compute.VirtualMachineCustomImage;
import com.microsoft.azure.management.compute.VirtualMachineExtension;
import com.microsoft.azure.management.compute.VirtualMachineSizeTypes;
import com.microsoft.azure.management.network.Network;
import com.microsoft.azure.management.network.NetworkInterface;
import com.microsoft.azure.management.network.NetworkSecurityGroup;
import com.microsoft.azure.management.network.NicIpConfiguration;
import com.microsoft.azure.management.network.PublicIpAddress;
import com.microsoft.azure.management.network.model.HasPrivateIpAddress;
import com.microsoft.azure.management.network.model.HasPublicIpAddress;
import com.microsoft.azure.management.resources.ResourceGroup;
import com.microsoft.azure.management.resources.fluentcore.arm.Region;
import com.microsoft.azure.management.resources.fluentcore.model.Creatable;
import com.microsoft.azure.management.resources.fluentcore.utils.SdkContext;

import lombok.Getter;
import lombok.extern.log4j.Log4j2;


/**
 * Provides Microsoft Azure clouds' management using the official java SDK.
 *
 * This class has been tested by ActiveEon to be thread safe (using Azure SDK release version 1.0.0-beta5).
 * However this need to be carefully double-checked after every SDK upgrades, as mentioned by Microsoft:
 * ------------------------------------------------------------------------------------------------------------
 * We do not make any thread-safety guarantees about our libraries. We also do not test them for thread-safety.
 * Methods that are currently thread-safe may be thread-unsafe in future versions.
 * ------------------------------------------------------------------------------------------------------------
 *
 * @author ActiveEon Team
 * @since 01/03/17
 */
@Component
@Log4j2
public abstract class AzureProvider implements CloudProvider {

    @Getter
    protected final String type = "azureAbstract";

    protected static final VirtualMachineSizeTypes DEFAULT_VM_SIZE = VirtualMachineSizeTypes.STANDARD_D1_V2;

    protected static final int RESOURCES_NAME_EXTRA_CHARS = 10;

    protected static final String VIRTUAL_NETWORK_NAME_BASE = "vnet";

    protected static final String PUBLIC_IP_ADDRESS_NAME_BASE = "ip";

    protected static final String NETWORK_SECURITY_GROUP_NAME_BASE = "sg";

    protected static final String NETWORK_INTERFACE_NAME_BASE = "if";

    protected static final String OS_DISK_NAME_BASE = "os";

    protected static final Boolean DEFAULT_STATIC_PUBLIC_IP = true;

    protected static final String SCRIPT_EXTENSION_PUBLISHER = "Microsoft.Azure.Extensions";

    protected static final String SCRIPT_EXTENSION_TYPE = "CustomScript";

    protected static final String SCRIPT_EXTENSION_VERSION = "2.0";

    protected static final String SCRIPT_EXTENSION_CMD_KEY = "commandToExecute";

    protected static final String SCRIPT_SEPARATOR = ";";

    protected static final String SINGLE_INSTANCE_NUMBER = "1";

    protected static final String INSTANCE_NOT_FOUND_ERROR = "ERROR unable to find instance with ID: ";

    @Autowired
    protected AzureServiceCache azureServiceCache;

    @Autowired
    protected AzureProviderUtils azureProviderUtils;

    @Autowired
    protected AzureProviderNetworkingUtils azureProviderNetworkingUtils;

    @Autowired
    protected TagManager tagManager;

    @Value("${connector-iaas.azure.default-username:activeeon}")
    protected String defaultUsername;

    @Value("${connector-iaas.azure.default-password:Act1v€0N}")
    protected String defaultPassword;

    @Value("${connector-iaas.azure.default-private-network-cidr:10.0.0.0/24}")
    protected String defaultPrivateNetworkCidr;

    @Override
    public Set<Instance> createInstance(Infrastructure infrastructure, Instance instance) {

        Azure azureService = azureServiceCache.getService(infrastructure);
        String instanceTag = Optional.ofNullable(instance.getTag())
                                     .orElseThrow(() -> new RuntimeException("ERROR missing instance tag/name from instance: '" +
                                                                             instance + "'"));

        // Check for Image by name first and then by id
        String imageNameOrId = Optional.ofNullable(instance.getImage())
                                       .orElseThrow(() -> new RuntimeException("ERROR missing Image name/id from instance: '" +
                                                                               instance + "'"));
        VirtualMachineCustomImage image = getImageByName(azureService,
                                                         imageNameOrId).orElseGet(() -> getImageById(azureService,
                                                                                                     imageNameOrId).orElseThrow(() -> new RuntimeException("ERROR unable to find custom Image: '" +
                                                                                                                                                           instance.getImage() +
                                                                                                                                                           "'")));

        // Get the options (Optional by design)
        Optional<Options> options = Optional.ofNullable(instance.getOptions());

        // Try to retrieve the resourceGroup from provided name, otherwise get it from image
        ResourceGroup resourceGroup = azureProviderUtils.searchResourceGroupByName(azureService,
                                                                                   options.map(Options::getResourceGroup)
                                                                                          .orElseGet(image::resourceGroupName))
                                                        .orElseThrow(() -> new RuntimeException("ERROR unable to find a suitable resourceGroup from instance: '" +
                                                                                                instance + "'"));

        // Try to get region from provided name, otherwise get it from image
        Region region = options.map(presentOptions -> Region.findByLabelOrName(presentOptions.getRegion()))
                               .orElseGet(image::region);

        // Prepare a new virtual private network (same for all VMs)
        Optional<String> optionalPrivateNetworkCIDR = options.map(Options::getPrivateNetworkCIDR);
        Creatable<Network> creatableVirtualNetwork = azureProviderNetworkingUtils.prepareVirtualNetwork(azureService,
                                                                                                        region,
                                                                                                        resourceGroup,
                                                                                                        createUniqueVirtualNetworkName(instanceTag),
                                                                                                        optionalPrivateNetworkCIDR.orElse(defaultPrivateNetworkCidr));

        // Get existing virtual private network if specified
        Optional<Network> optionalVirtualNetwork = options.map(Options::getSubnetId)
                                                          .map(subnetId -> azureProviderUtils.searchVirtualNetworkByName(azureService,
                                                                                                                         subnetId)
                                                                                             .get());

        // Prepare a new  security group (same for all VMs)
        Creatable<NetworkSecurityGroup> creatableNetworkSecurityGroup = azureProviderNetworkingUtils.prepareProactiveNetworkSecurityGroup(azureService,
                                                                                                                                          region,
                                                                                                                                          resourceGroup,
                                                                                                                                          createUniqueSecurityGroupName(instance.getTag()));

        // Get existing security group if specified
        Optional<NetworkSecurityGroup> optionalNetworkSecurityGroup = options.map(Options::getSecurityGroupNames)
                                                                             .map(secGrpNames -> secGrpNames.get(0))
                                                                             .map(secGrpName -> azureProviderUtils.searchNetworkSecurityGroupByName(azureService,
                                                                                                                                                    secGrpName)
                                                                                                                  .get());

        // Get existing public IP address if specified
        Optional<PublicIpAddress> optionalPublicIpAddress = options.map(Options::getPublicIpAddress)
                                                                   .map(publicIpAddress -> azureProviderUtils.searchPublicIpAddressByIp(azureService,
                                                                                                                                        publicIpAddress)
                                                                                                             .get());

        // Prepare the VM(s)
        Optional<Boolean> optionalStaticPublicIP = options.map(Options::getStaticPublicIP);

        AzureNetworkOptions networkOptions = new AzureNetworkOptions(creatableVirtualNetwork,
                                                                     optionalVirtualNetwork,
                                                                     creatableNetworkSecurityGroup,
                                                                     optionalNetworkSecurityGroup,
                                                                     optionalPublicIpAddress,
                                                                     optionalStaticPublicIP);

        List<Creatable<VirtualMachine>> creatableVirtualMachines = IntStream.rangeClosed(1,
                                                                                         Integer.valueOf(Optional.ofNullable(instance.getNumber())
                                                                                                                 .orElse(SINGLE_INSTANCE_NUMBER)))
                                                                            .mapToObj(instanceNumber -> {
                                                                                Creatable<NetworkInterface> creatableNetworkInterface = createPublicAddressAndNetworkInterface(azureService,
                                                                                                                                                                               instanceTag,
                                                                                                                                                                               resourceGroup,
                                                                                                                                                                               region,
                                                                                                                                                                               networkOptions,
                                                                                                                                                                               instanceNumber);
                                                                                return prepareVirtualMachine(instance,
                                                                                                             azureService,
                                                                                                             resourceGroup,
                                                                                                             region,
                                                                                                             createUniqueInstanceTag(instanceTag,
                                                                                                                                     instanceNumber),
                                                                                                             image,
                                                                                                             creatableNetworkInterface);
                                                                            })
                                                                            .collect(Collectors.toList());

        // Create all VMs in parallel and collect IDs
        return azureService.virtualMachines()
                           .create(creatableVirtualMachines)
                           .values()
                           .stream()
                           .map(vm -> instance.withTag(vm.name()).withId(vm.vmId()).withNumber(SINGLE_INSTANCE_NUMBER))
                           .collect(Collectors.toSet());
    }

    protected Creatable<NetworkInterface> createPublicAddressAndNetworkInterface(Azure azureService, String instanceTag,
            ResourceGroup resourceGroup, Region region, AzureNetworkOptions networkOptions, int instanceNumber) {
        // Create a new public IP address (one per VM)
        String publicIPAddressName = createUniquePublicIPName(createUniqueInstanceTag(instanceTag, instanceNumber));
        Creatable<PublicIpAddress> creatablePublicIpAddress = azureProviderNetworkingUtils.preparePublicIPAddress(azureService,
                                                                                                                  region,
                                                                                                                  resourceGroup,
                                                                                                                  publicIPAddressName,
                                                                                                                  networkOptions.getOptionalStaticPublicIP()
                                                                                                                                .orElse(DEFAULT_STATIC_PUBLIC_IP));

        // Prepare a new network interface (one per VM)
        String networkInterfaceName = createUniqueNetworkInterfaceName(createUniqueInstanceTag(instanceTag,
                                                                                               instanceNumber));
        return azureProviderNetworkingUtils.prepareNetworkInterface(azureService,
                                                                    region,
                                                                    resourceGroup,
                                                                    networkInterfaceName,
                                                                    networkOptions.getCreatableVirtualNetwork(),
                                                                    networkOptions.getOptionalVirtualNetwork()
                                                                                  .orElse(null),
                                                                    networkOptions.getCreatableNetworkSecurityGroup(),
                                                                    networkOptions.getOptionalNetworkSecurityGroup()
                                                                                  .orElse(null),
                                                                    creatablePublicIpAddress,
                                                                    instanceNumber == 1 ? networkOptions.getOptionalPublicIpAddress()
                                                                                                        .orElse(null)
                                                                                        : null);
    }

    protected Optional<VirtualMachineCustomImage> getImageByName(Azure azureService, String name) {
        return azureService.virtualMachineCustomImages()
                           .list()
                           .stream()
                           .filter(customImage -> customImage.name().equals(name))
                           .findAny();
    }

    protected Optional<VirtualMachineCustomImage> getImageById(Azure azureService, String id) {
        return azureService.virtualMachineCustomImages()
                           .list()
                           .stream()
                           .filter(customImage -> customImage.id().equals(id))
                           .findAny();
    }

    protected Creatable<VirtualMachine> prepareVirtualMachine(Instance instance, Azure azureService,
            ResourceGroup resourceGroup, Region region, String instanceTag, VirtualMachineCustomImage image,
            Creatable<NetworkInterface> creatableNetworkInterface) {

        // Configure the VM depending on the OS type
        VirtualMachine.DefinitionStages.WithFromImageCreateOptionsManaged creatableVirtualMachineWithImage;
        OperatingSystemTypes operatingSystemType = image.osDiskImage().osType();
        if (operatingSystemType.equals(OperatingSystemTypes.LINUX)) {
            creatableVirtualMachineWithImage = configureLinuxVirtualMachine(azureService,
                                                                            instanceTag,
                                                                            region,
                                                                            resourceGroup,
                                                                            instance.getCredentials(),
                                                                            image,
                                                                            creatableNetworkInterface);
        } else if (operatingSystemType.equals(OperatingSystemTypes.WINDOWS)) {
            creatableVirtualMachineWithImage = configureWindowsVirtualMachine(azureService,
                                                                              instanceTag,
                                                                              region,
                                                                              resourceGroup,
                                                                              instance.getCredentials(),
                                                                              image,
                                                                              creatableNetworkInterface);
        } else {
            throw new RuntimeException("ERROR Operating System of type '" + operatingSystemType.toString() +
                                       "' is not yet supported");
        }

        // Set VM size (or type) and name of OS' disk
        Optional<String> optionalHardwareType = Optional.ofNullable(instance.getHardware()).map(Hardware::getType);
        VirtualMachine.DefinitionStages.WithCreate creatableVMWithSize = creatableVirtualMachineWithImage.withSize(new VirtualMachineSizeTypes(optionalHardwareType.orElse(DEFAULT_VM_SIZE.toString())))
                                                                                                         .withOsDiskName(createUniqOSDiskName(instanceTag));

        // Add init script(s) using dedicated Microsoft extension
        Optional.ofNullable(instance.getInitScript()).map(InstanceScript::getScripts).ifPresent(scripts -> {
            if (scripts.length > 0) {
                StringBuilder concatenatedScripts = new StringBuilder();
                Lists.newArrayList(scripts)
                     .forEach(script -> concatenatedScripts.append(script).append(SCRIPT_SEPARATOR));
                creatableVMWithSize.defineNewExtension(createUniqueScriptName(instanceTag))
                                   .withPublisher(SCRIPT_EXTENSION_PUBLISHER)
                                   .withType(SCRIPT_EXTENSION_TYPE)
                                   .withVersion(SCRIPT_EXTENSION_VERSION)
                                   .withMinorVersionAutoUpgrade()
                                   .withPublicSetting(SCRIPT_EXTENSION_CMD_KEY, concatenatedScripts.toString())
                                   .attach();
            }
        });

        // Set tags
        return creatableVMWithSize.withTags(tagManager.retrieveAllTags(instance.getOptions())
                                                      .stream()
                                                      .collect(Collectors.toMap(Tag::getKey, Tag::getValue)));
    }

    protected VirtualMachine.DefinitionStages.WithLinuxCreateManaged configureLinuxVirtualMachine(Azure azureService,
            String instanceTag, Region region, ResourceGroup resourceGroup, InstanceCredentials instanceCredentials,
            VirtualMachineCustomImage image, Creatable<NetworkInterface> creatableNetworkInterface) {
        // Retrieve optional credentials
        Optional<String> optionalUsername = Optional.ofNullable(instanceCredentials)
                                                    .map(InstanceCredentials::getUsername);
        Optional<String> optionalPassword = Optional.ofNullable(instanceCredentials)
                                                    .map(InstanceCredentials::getPassword);
        Optional<String> optionalPublicKey = Optional.ofNullable(instanceCredentials)
                                                     .map(InstanceCredentials::getPublicKey);

        // Prepare the VM without credentials
        VirtualMachine.DefinitionStages.WithLinuxRootPasswordOrPublicKeyManaged creatableVMWithoutCredentials = azureService.virtualMachines()
                                                                                                                            .define(instanceTag)
                                                                                                                            .withRegion(region)
                                                                                                                            .withExistingResourceGroup(resourceGroup)
                                                                                                                            .withNewPrimaryNetworkInterface(creatableNetworkInterface)
                                                                                                                            .withLinuxCustomImage(image.id())
                                                                                                                            .withRootUsername(optionalUsername.orElse(defaultUsername));

        // Set the credentials (whether password or SSH key)
        return optionalPublicKey.map(creatableVMWithoutCredentials::withSsh)
                                .orElseGet(() -> creatableVMWithoutCredentials.withRootPassword(optionalPassword.orElse(defaultPassword)));
    }

    protected VirtualMachine.DefinitionStages.WithWindowsCreateManaged configureWindowsVirtualMachine(
            Azure azureService, String instanceTag, Region region, ResourceGroup resourceGroup,
            InstanceCredentials instanceCredentials, VirtualMachineCustomImage image,
            Creatable<NetworkInterface> creatableNetworkInterface) {
        // Retrieve optional credentials
        Optional<String> optionalUsername = Optional.ofNullable(instanceCredentials)
                                                    .map(InstanceCredentials::getUsername);
        Optional<String> optionalPassword = Optional.ofNullable(instanceCredentials)
                                                    .map(InstanceCredentials::getPassword);

        // Prepare the VM with credentials
        return azureService.virtualMachines()
                           .define(instanceTag)
                           .withRegion(region)
                           .withExistingResourceGroup(resourceGroup)
                           .withNewPrimaryNetworkInterface(creatableNetworkInterface)
                           .withWindowsCustomImage(image.id())
                           .withAdminUsername(optionalUsername.orElse(defaultUsername))
                           .withAdminPassword(optionalPassword.orElse(defaultPassword));
    }

    /**
     * Create a unique tag for a VM based on the original tag provided and the instance index
     *
     * @param tagBase       the tag base
     * @param instanceIndex the instance index
     * @return a unique VM tag
     */
    protected static String createUniqueInstanceTag(String tagBase, int instanceIndex) {
        if (instanceIndex > 1) {
            return tagBase + String.valueOf(instanceIndex);
        }
        return tagBase;
    }

    protected static String createUniqueSecurityGroupName(String instanceTag) {
        return createUniqueName(instanceTag, NETWORK_SECURITY_GROUP_NAME_BASE);
    }

    protected static String createUniqueVirtualNetworkName(String instanceTag) {
        return createUniqueName(instanceTag, VIRTUAL_NETWORK_NAME_BASE);
    }

    protected static String createUniqueNetworkInterfaceName(String instanceTag) {
        return createUniqueName(instanceTag, NETWORK_INTERFACE_NAME_BASE);
    }

    protected static String createUniquePublicIPName(String instanceTag) {
        return createUniqueName(instanceTag, PUBLIC_IP_ADDRESS_NAME_BASE);
    }

    protected static String createUniqOSDiskName(String instanceTag) {
        return createUniqueName(instanceTag, OS_DISK_NAME_BASE);
    }

    protected static String createUniqueScriptName(String instanceTag) {
        return createUniqueName(instanceTag, "");
    }

    protected static String createUniqueName(String customPart, String basePart) {
        return SdkContext.randomResourceName(customPart + '-' + basePart,
                                             customPart.length() + basePart.length() + 1 + RESOURCES_NAME_EXTRA_CHARS);
    }

    @Override
    public void deleteInstance(Infrastructure infrastructure, String instanceId) {
        Azure azureService = azureServiceCache.getService(infrastructure);

        VirtualMachine vm = azureProviderUtils.searchVirtualMachineByID(azureService, instanceId)
                                              .orElseThrow(() -> new RuntimeException(INSTANCE_NOT_FOUND_ERROR + "'" +
                                                                                      instanceId + "'"));

        log.info("Deletion of all Azure resources of instance " + instanceId +
                 " is being requested to the provider (infrastructure: " + infrastructure.getId() + ")");

        // Retrieve all resources attached to the instance
        List<com.microsoft.azure.management.network.Network> networks = azureProviderNetworkingUtils.getVMNetworks(azureService,
                                                                                                                   vm);
        List<NetworkSecurityGroup> networkSecurityGroups = azureProviderNetworkingUtils.getVMSecurityGroups(azureService,
                                                                                                            vm);
        List<PublicIpAddress> publicIPAddresses = azureProviderNetworkingUtils.getVMPublicIPAddresses(azureService, vm);
        String osDiskID = vm.osDiskId();

        // Delete the VM first
        azureService.virtualMachines().deleteById(vm.id());

        // Then delete all network interfaces attached
        vm.networkInterfaceIds().forEach(id -> azureService.networkInterfaces().deleteById(id));

        // Delete all public IP addresses
        publicIPAddresses.stream()
                         .map(PublicIpAddress::id)
                         .forEach(id -> azureService.publicIpAddresses().deleteById(id));

        // Delete its main disk (OS), *and keep data disks*
        azureService.disks().deleteById(osDiskID);

        // Delete the security groups if not attached to any remaining network interface
        deleteSecurityGroups(azureService, networkSecurityGroups);

        // Delete the virtual networks if not attached to any remaining network interface
        deleteNetworks(azureService, networks);

        log.info("Deletion of all Azure resources of instance " + instanceId + " has been executed.");
    }

    protected void deleteSecurityGroups(Azure azureService, List<NetworkSecurityGroup> networkSecurityGroups) {
        // Delete the security groups if not attached to any remaining network interface
        networkSecurityGroups.stream()
                             .map(NetworkSecurityGroup::id)
                             .filter(id -> azureService.networkInterfaces()
                                                       .list()
                                                       .stream()
                                                       .map(NetworkInterface::getNetworkSecurityGroup)
                                                       .filter(Objects::nonNull)
                                                       .noneMatch(networkSecurityGroup -> networkSecurityGroup.id()
                                                                                                              .equals(id)))
                             .forEach(id -> azureService.networkSecurityGroups().deleteById(id));
    }

    protected void deleteNetworks(Azure azureService, List<com.microsoft.azure.management.network.Network> networks) {
        networks.stream()
                .map(Network::id)
                .filter(id -> azureService.networkInterfaces()
                                          .list()
                                          .stream()
                                          .flatMap(networkInterface -> networkInterface.ipConfigurations()
                                                                                       .values()
                                                                                       .stream())
                                          .filter(Objects::nonNull)
                                          .map(NicIpConfiguration::getNetwork)
                                          .filter(Objects::nonNull)
                                          .noneMatch(network -> network.id().equals(id)))
                .forEach(id -> azureService.networks().deleteById(id));
    }

    @Override
    public Set<Instance> getAllInfrastructureInstances(Infrastructure infrastructure) {
        Azure azureService = azureServiceCache.getService(infrastructure);
        return getInstancesFromVMs(azureService, azureProviderUtils.getAllVirtualMachines(azureService));
    }

    @Override
    public Set<Instance> getCreatedInfrastructureInstances(Infrastructure infrastructure) {
        Azure azureService = azureServiceCache.getService(infrastructure);
        Tag connectorIaasTag = tagManager.getConnectorIaasTag();
        return getInstancesFromVMs(azureService, azureProviderUtils.getAllVirtualMachines(azureService)
                                                                   .stream()
                                                                   .filter(vm -> vm.tags().keySet().contains(
                                                                                                             connectorIaasTag.getKey()) &&
                                                                                 vm.tags()
                                                                                   .get(connectorIaasTag.getKey())
                                                                                   .equals(connectorIaasTag.getValue()))
                                                                   .collect(Collectors.toSet()));
    }

    protected Set<Instance> getInstancesFromVMs(Azure azureService, Set<VirtualMachine> vms) {
        return vms.stream()
                  .map(vm -> Instance.builder()
                                     .id(vm.vmId())
                                     .tag(vm.name())
                                     .number(SINGLE_INSTANCE_NUMBER)
                                     .hardware(Hardware.builder().type(vm.size().toString()).build())
                                     .network(org.ow2.proactive.connector.iaas.model.Network.builder()
                                                                                            .publicAddresses(buildPublicAddressList(azureService,
                                                                                                                                    vm))
                                                                                            .privateAddresses(buildPrivateAddressList(azureService,
                                                                                                                                      vm))
                                                                                            .build())
                                     .status(String.valueOf(vm.powerState().toString()))
                                     .build())
                  .collect(Collectors.toSet());
    }

    protected List<String> buildPrivateAddressList(Azure azureService, VirtualMachine vm) {
        return vm.networkInterfaceIds()
                 .stream()
                 .map(networkInterfaceId -> azureService.networkInterfaces().getById(networkInterfaceId))
                 .flatMap(networkInterface -> networkInterface.ipConfigurations().values().stream())
                 .filter(Objects::nonNull)
                 .map(HasPrivateIpAddress::privateIpAddress)
                 .collect(Collectors.toList());
    }

    protected List<String> buildPublicAddressList(Azure azureService, VirtualMachine vm) {
        return vm.networkInterfaceIds()
                 .stream()
                 .map(networkInterfaceId -> azureService.networkInterfaces().getById(networkInterfaceId))
                 .map(NetworkInterface::primaryIpConfiguration)
                 .filter(Objects::nonNull)
                 .map(HasPublicIpAddress::getPublicIpAddress)
                 .filter(Objects::nonNull)
                 .map(PublicIpAddress::ipAddress)
                 .collect(Collectors.toList());
    }

    @Override
    public List<ScriptResult> executeScriptOnInstanceId(Infrastructure infrastructure, String instanceId,
            InstanceScript instanceScript) {
        VirtualMachine vm = azureProviderUtils.searchVirtualMachineByID(azureServiceCache.getService(infrastructure),
                                                                        instanceId)
                                              .orElseThrow(() -> new RuntimeException(INSTANCE_NOT_FOUND_ERROR + "'" +
                                                                                      instanceId + "'"));
        return executeScriptOnVM(vm, instanceScript);
    }

    @Override
    public List<ScriptResult> executeScriptOnInstanceTag(Infrastructure infrastructure, String instanceTag,
            InstanceScript instanceScript) {
        VirtualMachine vm = azureProviderUtils.searchVirtualMachineByName(azureServiceCache.getService(infrastructure),
                                                                          instanceTag)
                                              .orElseThrow(() -> new RuntimeException("ERROR unable to find instance with name: '" +
                                                                                      instanceTag + "'"));
        return executeScriptOnVM(vm, instanceScript);
    }

    protected List<ScriptResult> executeScriptOnVM(VirtualMachine vm, InstanceScript instanceScript) {

        // Concatenate all provided scripts in one (Multiple VMExtensions per handler not supported)
        StringBuilder concatenatedScripts = new StringBuilder();
        Arrays.stream(instanceScript.getScripts()).forEach(script -> {
            concatenatedScripts.append(script).append(SCRIPT_SEPARATOR);
        });

        Optional<VirtualMachineExtension> vmExtension = vm.extensions()
                                                          .values()
                                                          .stream()
                                                          .filter(extension -> extension.publisherName()
                                                                                        .equals(SCRIPT_EXTENSION_PUBLISHER) &&
                                                                               extension.typeName()
                                                                                        .equals(SCRIPT_EXTENSION_TYPE))
                                                          .findAny();
        if (vmExtension.isPresent()) {
            // if the script didn't change compared to the previous update,
            // then the script will not be run by the Azure provider.
            // Moreover, the 'forceUpdateTag' is not available in the Azure
            // Java SDK. So in order to make sure that the script slightly
            // changes, we make the scripts begin with a command that prints
            // a new ID each time a script needs to be run after the first
            // executed script.
            UUID scriptExecutionId = UUID.randomUUID();
            String concatenatedScriptsWithExecutionId = concatenatedScripts.insert(0,
                                                                                   "echo script-ID" + SCRIPT_SEPARATOR +
                                                                                      "echo " + scriptExecutionId +
                                                                                      SCRIPT_SEPARATOR)
                                                                           .toString();
            log.info("Request Azure provider to execute script: " + concatenatedScriptsWithExecutionId);
            vm.update()
              .updateExtension(vmExtension.get().name())
              .withPublicSetting(SCRIPT_EXTENSION_CMD_KEY, concatenatedScriptsWithExecutionId)
              .parent()
              .apply();
            log.debug("Execution of script has been requested.");
        } else {
            log.info("Request Azure provider to install script extension and to execute script: " +
                     concatenatedScripts.toString());
            vm.update()
              .defineNewExtension(createUniqueScriptName(vm.name()))
              .withPublisher(SCRIPT_EXTENSION_PUBLISHER)
              .withType(SCRIPT_EXTENSION_TYPE)
              .withVersion(SCRIPT_EXTENSION_VERSION)
              .withMinorVersionAutoUpgrade()
              .withPublicSetting(SCRIPT_EXTENSION_CMD_KEY, concatenatedScripts.toString())
              .attach()
              .apply();
            log.debug("Installation of script extension and execution of script has been requested.");
        }

        // Unable to retrieve scripts output, returns empty results instead
        return IntStream.rangeClosed(1, instanceScript.getScripts().length)
                        .mapToObj(scriptNumber -> new ScriptResult(vm.vmId(), "", ""))
                        .collect(Collectors.toList());
    }

    @Override
    public Set<Image> getAllImages(Infrastructure infrastructure) {
        return azureServiceCache.getService(infrastructure)
                                .virtualMachineCustomImages()
                                .list()
                                .stream()
                                .map(azureImage -> Image.builder().id(azureImage.id()).name(azureImage.name()).build())
                                .collect(Collectors.toSet());
    }

    @Override
    public void deleteInfrastructure(Infrastructure infrastructure) {
        azureServiceCache.removeService(infrastructure);
    }

    @Override
    public String addToInstancePublicIp(Infrastructure infrastructure, String instanceId, String optionalDesiredIp) {
        Azure azureService = azureServiceCache.getService(infrastructure);
        VirtualMachine vm = azureProviderUtils.searchVirtualMachineByID(azureService, instanceId)
                                              .orElseThrow(() -> new RuntimeException(INSTANCE_NOT_FOUND_ERROR + "'" +
                                                                                      instanceId + "'"));
        ResourceGroup resourceGroup = azureService.resourceGroups().getByName(vm.resourceGroupName());

        // Try to retrieve the desired public IP address or create a new one
        PublicIpAddress publicIpAddress = Optional.ofNullable(optionalDesiredIp)
                                                  .map(opt -> azureService.publicIpAddresses()
                                                                          .list()
                                                                          .stream()
                                                                          .filter(availablePublicIP -> availablePublicIP.ipAddress()
                                                                                                                        .equals(opt))
                                                                          .findAny()
                                                                          .get())
                                                  .orElseGet(() -> azureProviderNetworkingUtils.preparePublicIPAddress(azureService,
                                                                                                                       vm.region(),
                                                                                                                       resourceGroup,
                                                                                                                       createUniquePublicIPName(vm.name()),
                                                                                                                       DEFAULT_STATIC_PUBLIC_IP)
                                                                                               .create());

        List<NetworkInterface> networkInterfaces = vm.networkInterfaceIds()
                                                     .stream()
                                                     .map(id -> azureService.networkInterfaces().getById(id))
                                                     .collect(Collectors.toList());

        // If all existing network interfaces already have a public IP, then create a new/secondary network interface
        if (networkInterfaces.stream()
                             .allMatch(netIf -> Optional.ofNullable(netIf.primaryIpConfiguration()).isPresent() &&
                                                Optional.ofNullable(netIf.primaryIpConfiguration().getPublicIpAddress())
                                                        .isPresent())) {
            // Reuse the network configuration (virtual private network & security group) of the primary network interface
            addPublicIpWithNewSecondaryNetworkInterface(azureService, vm, resourceGroup, publicIpAddress);
            // Otherwise add the public address to the first network interface without public IP
        } else {
            networkInterfaces.stream()
                             .filter(netIf -> !Optional.ofNullable(netIf.primaryIpConfiguration()).isPresent() ||
                                              !Optional.ofNullable(netIf.primaryIpConfiguration().getPublicIpAddress())
                                                       .isPresent())
                             .findFirst()
                             .ifPresent(pubIpAddr -> pubIpAddr.update()
                                                              .withExistingPrimaryPublicIpAddress(publicIpAddress)
                                                              .apply());
        }

        return publicIpAddress.ipAddress();
    }

    protected void addPublicIpWithNewSecondaryNetworkInterface(Azure azureService, VirtualMachine vm,
            ResourceGroup resourceGroup, PublicIpAddress publicIpAddress) {
        // Reuse the network configuration (virtual private network & security group) of the primary network interface
        NetworkInterface networkInterface = vm.getPrimaryNetworkInterface();
        NetworkSecurityGroup networkSecurityGroup = networkInterface.getNetworkSecurityGroup();
        com.microsoft.azure.management.network.Network network = networkInterface.primaryIpConfiguration().getNetwork();
        NetworkInterface newSecondaryNetworkInterface = azureProviderNetworkingUtils.prepareNetworkInterface(azureService,
                                                                                                             vm.region(),
                                                                                                             resourceGroup,
                                                                                                             createUniqueNetworkInterfaceName(vm.name()),
                                                                                                             network,
                                                                                                             networkSecurityGroup,
                                                                                                             publicIpAddress)
                                                                                    .create();
        try {
            vm.update().withExistingSecondaryNetworkInterface(newSecondaryNetworkInterface).apply();
        } catch (RuntimeException ex) {
            log.warn("Cannot add new network interface. Remove it and modify the primary network interface instead",
                     ex);
            handleAddPublicIpWithNewSecondaryNetworkInterfaceException(azureService,
                                                                       vm,
                                                                       publicIpAddress,
                                                                       newSecondaryNetworkInterface);
        }
    }

    protected void handleAddPublicIpWithNewSecondaryNetworkInterfaceException(Azure azureService, VirtualMachine vm,
            PublicIpAddress publicIpAddress, NetworkInterface newSecondaryNetworkInterface) {
        removePublicIpFromNetworkInterface(azureService, newSecondaryNetworkInterface);
        replaceVMPrimaryPublicIpAddress(azureService, vm, publicIpAddress);
    }

    protected void removePublicIpFromNetworkInterface(Azure azureService, NetworkInterface networkInterface) {
        networkInterface.update().withoutPrimaryPublicIpAddress().apply();
        azureService.networkInterfaces().deleteById(networkInterface.id());
    }

    protected void replaceVMPrimaryPublicIpAddress(Azure azureService, VirtualMachine vm,
            PublicIpAddress newPublicIpAddress) {
        PublicIpAddress existingPublicIPAddress = vm.getPrimaryPublicIpAddress();
        vm.getPrimaryNetworkInterface().update().withoutPrimaryPublicIpAddress().apply();
        azureService.publicIpAddresses().deleteById(existingPublicIPAddress.id());
        vm.getPrimaryNetworkInterface().update().withExistingPrimaryPublicIpAddress(newPublicIpAddress).apply();
    }

    @Override
    public void removeInstancePublicIp(Infrastructure infrastructure, String instanceId, String optionalDesiredIp) {
        Azure azureService = azureServiceCache.getService(infrastructure);
        VirtualMachine vm = azureProviderUtils.searchVirtualMachineByID(azureService, instanceId)
                                              .orElseThrow(() -> new RuntimeException(INSTANCE_NOT_FOUND_ERROR + "'" +
                                                                                      instanceId + "'"));
        Optional<PublicIpAddress> optionalPublicIpAddress = Optional.ofNullable(optionalDesiredIp)
                                                                    .map(opt -> azureService.publicIpAddresses()
                                                                                            .list()
                                                                                            .stream()
                                                                                            .filter(availablePublicIP -> availablePublicIP.ipAddress()
                                                                                                                                          .equals(opt))
                                                                                            .findAny()
                                                                                            .get());

        // Delete the desired IP address if present
        if (optionalPublicIpAddress.isPresent()) {
            azureService.publicIpAddresses().deleteById(optionalPublicIpAddress.get().id());
            return;
        }

        // If there is a secondary interface with a public IP then remove it in prior
        Optional<NetworkInterface> optionalSecondaryNetworkInterface = vm.networkInterfaceIds()
                                                                         .stream()
                                                                         .map(networkInterfaceId -> azureService.networkInterfaces()
                                                                                                                .getById(networkInterfaceId))
                                                                         .filter(networkInterface -> Optional.ofNullable(networkInterface.primaryIpConfiguration()
                                                                                                                                         .getPublicIpAddress())
                                                                                                             .isPresent())
                                                                         .filter(networkInterface -> !networkInterface.id()
                                                                                                                      .equals(vm.getPrimaryPublicIpAddressId()))
                                                                         .findAny();
        if (optionalSecondaryNetworkInterface.isPresent()) {
            PublicIpAddress publicIPAddress = optionalSecondaryNetworkInterface.get()
                                                                               .primaryIpConfiguration()
                                                                               .getPublicIpAddress();
            optionalSecondaryNetworkInterface.get().update().withoutPrimaryPublicIpAddress().apply();
            azureService.publicIpAddresses().deleteById(publicIPAddress.id());
        }
        // Otherwise remove the public IP address from the primary interface if present
        else if (Optional.ofNullable(vm.getPrimaryPublicIpAddress()).isPresent()) {
            PublicIpAddress publicIPAddress = vm.getPrimaryPublicIpAddress();
            vm.getPrimaryNetworkInterface().update().withoutPrimaryPublicIpAddress().apply();
            azureService.publicIpAddresses().deleteById(publicIPAddress.id());
        }
    }

    @Override
    public SimpleImmutableEntry<String, String> createKeyPair(Infrastructure infrastructure, Instance instance) {
        throw new UnsupportedOperationException();
    }
}
