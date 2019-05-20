# Connector IaaS
[![Build Status](http://jenkins.activeeon.com/buildStatus/icon?job=connector-iaas)](http://jenkins.activeeon.com/job/connector-iaas/)

## IaaS deployment

   IaaS deployment is a solution which enables to use any provider. It improves flexibility and reduces the provider dependencies by giving the migration very easily to do from one provider to another.
   The multi-IaaS connector enables to do CRUD operations on different infrastructures on public or private Cloud (AWS EC2, Openstack, VMWare, Docker, etc). It is connected with those infrastructure interfaces in order to manage the virtual machines lifecycle.

##Build and run

On linux, in the root project directory,

Building the project
```
     $ ./gradlew
```

Running the application
```
    $ ./gradlew run
```

## Manage infrastructures

### List supported infrastructures
```
    $ curl -k -X GET http://{IP_ADDRESS}/infrastructures
```

### Save an infrastructure

Generic information for saving an infrastructure are :
- id: the name of the given infrastructure in order to identify it
- type: the type is cloud nature or the hypervisor used (openstack-nova, aws-ec2, vmware, etc.)
- endpoint: the reference to the interface which manage the infrastructure
- credentials: the information which enables to connect

#### Openstack

For openstack, credentials information are made of the login and the password.

For saving an openstack infrastructure (in JSON), the information are :

```javascript
{
  "id": "OPENSTACK_INFRASTRUCTURE_ID",
  "type": "openstack-nova",
  "endpoint": "http://IP_ADDRESS:5000/v2.0/",
  "credentials": {
    "username": "NAME:LOGIN",
    "password": "PWD"
  }
}
```

The curl command for save this infrastructure with the IaaS connector is :
```
    $ curl -i -H "Accept: application/json" -H "Content-Type: application/json" -X POST -d '{"id": "OPENSTACK_INFRASTRUCTURE_ID","type": "openstack-nova","endPoint": "http://IP_ADDRESS:5000/v2.0/", "credentials": { "username": "NAME:LOGIN", "password": "PWD" }}' http://IP_ADDRESS:9080/infrastructures
```

#### VMware

For saving a VMware infrastructure (in JSON), the information are :

```javascript
{
  "id": "VMWARE_INFRASTRUCTURE_ID",
  "type": "vmware",
  "endpoint": "https://IP_ADDRESS/sdk",
  "credentials": {
    "username": "NAME",
    "password": "PWD"
  }
}
```

```
    $ curl -i -H "Accept: application/json" -H "Content-Type: application/json" -X POST -d '{"id": "VMWARE_INFRASTRUCTURE_ID","type": "vmware","endPoint": "https://IP_ADDRESS/sdk", "credentials": { "username": "NAME", "password": "PWD" }}' http://IP_ADDRESS:9080/infrastructures
```

#### AWS-EC2
An infrastructure AWS-EC2 needs to have an AWS account. Once the account is created, the user will have an AWS-key (used as a login) and a AWS-secret-key (used as a password). Unlike Openstack, VMware, the AWS-EC2 infrastructure creation doesn’t need to reference to the interface which enables to manage the infrastructure (ip adress and port).

For saving a EC2 infrastructure (in JSON), the information are :

```javascript
{
  "id": "AWS_INFRASTRUCTURE_ID",
  "type": "aws-ec2",
  "credentials": {
    "username": "NAME",
    "password": "PWD"
  }
}
```

```
   $ curl -i -H "Accept: application/json" -H "Content-Type: application/json" -X POST -d '{"id": "AWS_INFRASTRUCTURE_ID","type": "aws-ec2","credentials": { "username": "NAME", "password": "PWD" }}' http://IP_ADDRESS:9080/infrastructures
```

### Update a supported infrastructure

An infrastructure can be updated by posting the same infrastructure (same id) with the changes.

```
   $ curl -i -H "Accept: application/json" -H "Content-Type: application/json" -X POST -d '{"id":"AWS_INFRASTRUCTURE_ID","type": "aws-ec2","credentials": { "username": "NAME", "password": "PWD" }}' http://IP_ADDRESS:9080/infrastructures
```

### Delete a supported infrastructure

An infrastructure can be deleted by giving the id in the following command :

```
   $ curl -X DELETE http://IP_ADDRESS:9080/infrastructures/INFRASTRUCTURE_ID
```
### List the images supported by the infrastructure

```
    $ curl -k -X GET http://IP_ADDRESS:9080/infrastructures/INFRASTRUCTURE_ID/images
```

### Manage the lifecycle of virtual machines
Once the infrastructure is saved, the virtual machines can be managed.

### Create an instance
The generic information for creating one or several instances are :
- tag: the instance name for identifying them
- image: the image to use
- number: the number of instances to deploy
- hardware: the information related to the ram or the cpu

```javascript
{
 "tag": "TAG",
 "image": "IMAGE",
 "number": "1",
 "hardware": {
   "minRam": "1024",
   "minCores" : "1"
 }
}
```
```
    $ curl -i -H "Accept: application/json" -H "Content-Type: application/json" -X POST -d '{"tag":"TAG","image":"IMAGE","number":"1","minCores":"1","minRam":"1024"}' http://IP_ADDRESS:9080/infrastructures/INFRASTRUCTURE_ID/instances
```

### Create an instance with a specific keyname
```
$ curl -H "Accept: application/json" -H "Content-Type: application/json" -X POST -d '{"tag": "instancetest", "image": "eu-west-1/ami-00035f41c82244dab", "number": "1","credentials":{"publicKeyName":"pansaws","username":"ubuntu"}, "hardware": {"minRam":"512", "minCores":"1"}}' http://IP_ADDRESS:9080/infrastructures/INFRASTRUCTURE_ID/instances
```

### Create an instance with a specific keyname and group
If the group is not related to an existing VPC, you are not obliged to mention the network Id

```
  curl -H "Accept: application/json" -H "Content-Type: application/json" -X POST -d '{"tag": "instancetest", "image": "eu-west-1/ami-00035f41c82244dab", "number": "1","credentials":{"publicKeyName":"pansaws","username":"ubuntu"}, "options":{"securityGroupNames":["sg-xxxxx"]}, "hardware": {"minRam":"512", "minCores":"1"}}' http://IP_ADDRESS:9080/infrastructures/INFRASTRUCTURE_ID/instances
```

### Create an instance with a specific keyname, group and network

```
 curl -H "Accept: application/json" -H "Content-Type: application/json" -X POST -d '{"tag": "instancetest", "image": "eu-west-1/ami-00035f41c82244dab", "number": "1", "credentials": {"publicKeyName":"pansaws","username":"ubuntu"}, "options" :{"securityGroupNames": ["sg-xxxxx"],"subnetId":"subnet-ed82189a"}, "hardware": {"minRam":"512", "minCores":"1"}}' http://IP_ADDRESS:9080/infrastructures/INFRASTRUCTURE_ID/instances
```


### List an infrastructure instances
```
    $ curl -k -X GET http://IP_ADDRESS:9080/infrastructures/INFRASTRUCTURE_ID/instances
```
