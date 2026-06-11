echo "Running Liquibase"
dbServerName=$1
dbUserName=$2
dbPassword=$3
contextName=stagingdlrm
dbPort=${5:-5432}
java -jar event-repository-liquibase.jar --url=jdbc:postgresql://${dbServerName}:${dbPort}/${contextName}eventstore?sslmode=require --username=${dbUserName} --password=${dbPassword} --logLevel=info update
if [ $? -ne 0 ]
then
    exit 1
else
    echo success!
fi
java -jar aggregate-snapshot-repository-liquibase.jar --url=jdbc:postgresql://${dbServerName}:${dbPort}/${contextName}eventstore?sslmode=require --username=${dbUserName} --password=${dbPassword} --logLevel=info update
if [ $? -ne 0 ]
then
    exit 1
else
    echo success!
fi
java -jar event-buffer-liquibase.jar --url=jdbc:postgresql://${dbServerName}:${dbPort}/${contextName}viewstore?sslmode=require --username=${dbUserName} --password=${dbPassword} --logLevel=info update
if [ $? -ne 0 ]
then
    exit 1
else
    echo success!
fi
java -jar event-tracking-liquibase.jar --url=jdbc:postgresql://${dbServerName}:${dbPort}/${contextName}viewstore?sslmode=require --username=${dbUserName} --password=${dbPassword} --logLevel=info update
if [ $? -ne 0 ]
then
    exit 1
else
    echo success!
fi
java -jar stagingdlrm-viewstore-liquibase.jar --url=jdbc:postgresql://${dbServerName}:${dbPort}/${contextName}viewstore?sslmode=require --username=${dbUserName} --password=${dbPassword} --logLevel=info update
if [ $? -ne 0 ]
then
    exit 1
else
    echo success!
fi
java -jar framework-system-liquibase.jar --url=jdbc:postgresql://${dbServerName}:${dbPort}/${contextName}system?sslmode=require --username=${dbUserName} --password=${dbPassword} --logLevel=info update
if [ $? -ne 0 ]
then
    exit 1
else
    echo success!
fi