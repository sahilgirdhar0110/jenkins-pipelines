def versionFront()
{
    build_version = "${params.SERVICE_VERSION}"
	echo build_version
	return build_version
}


def readVersion(pomPath)
{
      
   // pom = readMavenPom file: 'pom.xml'
    pom = readMavenPom file: "${pomPath}"
    
	return  pom.version

}

def version(pomPath)
{
    
	def matcher = readFile("${pomPath}") =~ '<version>(.+?)</version>'
    matcher ? matcher[0][1] : null
    return matcher[0][1]
}

def versionTportal(pomPath)
{
    def matcher = readFile("${pomPath}") =~ /(?s)\s*<parent>.*<version>(.*)<\/version>.*<\/parent>\s*/
    matcher ? matcher[0][1] : null
    return matcher[0][1]
}

def tokenRefresh()
{
	
	sh 'rm  ~/.dockercfg || true'
    sh 'rm ~/.docker/config.json || true'
	sh 'eval $(aws ecr get-login --no-include-email --region eu-west-1)'
}

def cloneBuildCore(core_branch_name,core_repo_url){
    git branch: "${core_branch_name}",
    url: "${core_repo_url}"
    sh 'mvn -f pom.xml clean install -DskipTests -Dmaven.wagon.http.ssl.insecure=true -Dmaven.wagon.http.ssl.allowall=true -Dmaven.wagon.http.ssl.ignore.validity.dates=true -Dmaven.wagon.http.ssl.ignore.validity.dates=true'
}


def buildApplication(core_branch_name, app_branch_name,repo_name,app_pom_file_path,core_url)
{
    if ("${core_branch_name}".isEmpty() != true)
    {
    	stage('Git clone and Build Core')
        {
        echo "*****************************Core branch build started******************************"
	      cloneBuildCore("${core_branch_name}","${core_url}") 
        echo "*****************************Core branch build ended********************************"


		}
	} 
	stage('Git Clone')
		{
			git branch: "${app_branch_name}",
			url: "${repo_name}"
		}
	stage('Build Application')
		{
			echo "*************************Application build started******************************"
		 	sh "mvn -f  ${app_pom_file_path} clean install -Dmaven.wagon.http.ssl.insecure=true -Dmaven.wagon.http.ssl.allowall=true  -DskipTests"
      echo "*******************************Application build ended********************************"	
		}
}

def cloneApplicationCode(app_branch_name,repo_name)
{
    stage('Git Clone')
		{
			git branch: "${app_branch_name}",
			url: "${repo_name}"
		}
	
      
		
}

def buildPushDockerImage(ecr_repo_name,docker_file_path,service_version,registry_name,env)
{
	tokenRefresh()
	docker.withRegistry("https://${registry_name}" )
	{
		docker.build(ecr_repo_name +":${service_version}","-f ${docker_file_path} .").push("$service_version")
	}
}

def triggerDeployOnDev(service_name,service_version,ansible_service_parameter,ansible_var_file,pem_key_path,service_deployment_script,env_name)
{
	
	stage ("Deploying ${service_name} service  on ${env_name}")
	{
		def workdir = pwd()
		echo "workspace directory  ${workdir}"
		sh "sed -i s/${ansible_service_parameter}/${service_version}/g ${workdir}@script/oaknorth-ansible/group_vars/${ansible_var_file}"
		sh "chmod +x ${workdir}@script/oaknorth-ansible/ec2.py"
		sh "ansible-playbook -i ${workdir}@script/oaknorth-ansible/ec2.py --private-key=/var/ansible/playbooks/${pem_key_path} ${workdir}@script/oaknorth-ansible/${service_deployment_script}"
	}
}



def triggerDeployOnTestAndPrelive(deploy_job_name,service_version,jenkin_server_url,jenkin_server_user_token,jenkin_server_user)
{
	
	stage ("Triggering ${deploy_job_name} Job on ${params.DEPLOY_ENV}")
	{
		def handle = triggerRemoteJob job: "${jenkin_server_url}/job/${deploy_job_name}", parameters: "service_version=${service_version}",auth: TokenAuth(apiToken: "${jenkin_server_user_token}", userName: "${jenkin_server_user}")
    while( !handle.isFinished() )
			{
				echo 'Current Status: ' + handle.getBuildStatus().toString();
				sleep 3
            }
            echo handle.getBuildStatus().toString();

	}
}


def verifyDeploymentOnDev(cluster_name,service_name,dev_health_check_url,dev_sns_topic_arn,environment)
{
  stage("Verifying deployment")
  {
  def workdir = pwd()
  echo "workspace directory  ${workdir}"
  sh "chmod +x ${workdir}@script/python-scripts/validate-deployent.py"
  sh "python ${workdir}@script/python-scripts/validate-deployent.py --healthcheckurl ${dev_health_check_url} --env ${environment} --clustername ${cluster_name} --topicarn ${dev_sns_topic_arn} --servicename ${service_name}"
  }
}

def verifyDeploymentOnTestAndPrelive(job_name,jenkin_server_url,jenkin_server_user_token,jenkin_server_user)
{
 stage('Triggering Monitor Job')
 {
  def handle = triggerRemoteJob job: "${jenkin_server_url}/job/${job_name}",blockBuildUntilComplete: false,auth: TokenAuth(apiToken: "${jenkin_server_user_token}", userName: "${jenkin_server_user}")
 }
}


def notifyBuild(String buildStatus = 'STARTED',failed_job_reciepients,passed_job_reciepients) 
{
   
  buildStatus =  buildStatus ?: 'SUCCESSFUL'
   
  def colorName = 'RED'
  def colorCode = '#FF0000'
  def subject = "${buildStatus}: Pipeline '${env.JOB_NAME} [${env.BUILD_NUMBER}]' for ${params.DEPLOY_ENV} environment"
  def details = """<p> Check attached output for more details</p>"""
    
       
  if (buildStatus == 'STARTED') {
       echo "Build started"   
   } 
   
  else if (buildStatus == 'SUCCESSFUL') 
  {
   	emailext (attachLog: true,subject: subject,body: details,to: "${passed_job_reciepients}")
  } 
  else 
  {
    emailext (attachLog: true,subject: subject,body: details,to: "${failed_job_reciepients}"
     )
  }
 
   }
return this;
