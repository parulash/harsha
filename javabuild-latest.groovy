import com.hsbc.group.tooling.jenkins.build.Maven
import com.hsbc.group.tooling.jenkins.build.Docker

@groovy.transform.Field def workspaceDir
@groovy.transform.Field def config
@groovy.transform.Field def docker
@groovy.transform.Field def dockerNexus3Tag

def call(Map config) {
    this.config = config
    def exec_node = (config.exec_node) ? config.exec_node : 'gce-us-central1-a-d-cmbdigonb-jenkins-slave'
    def test_node2 = (config.test_node2) ? config.test_node2 : 'cm-linux-cjoc'
    node(exec_node) {
        try {
            deleteDir()
            this.createGitWorkspace()
            workspaceDir = (config.workspaceDir) ? config.workspaceDir : '.'
    
            dir(workspaceDir) {
                
                this.executeMvnBuild()
            }
        } catch (Throwable t) {
            logger.error(" Error in executing build step, exception is :" + t.getMessage());
        }
    }
}

private createGitWorkspace() {
    logger.trace("vars/hsbcMvnDockerCI::createGitWorkspace() Start.")
    def gitHubBaseURL = (env.GITHUB_BASE_URL) ? env.GITHUB_BASE_URL : 'https://alm-github.systems.uk.hsbc'

    logger.info("GitHub Code Checkout Stage Start")
    Map scmConfig = [gitCredsID: 'ALM_GITHUB_CREDS',
                     gitURL: "${gitHubBaseURL}/${this.config.gitRepo}.git",
                     branch: this.config.gitBranchOrTagOrCommitId,
                     componentFolder: '.']
    logger.debug("GitHub Configuration:${scmConfig}")
    gitTools().gitCheckout(scmTool: scmConfig)
    logger.info("GitHub Code Checkout Stage Complete")
    logger.trace("vars/hsbcMvnDockerCI::createGitWorkspace() Start.")
}

private def executeMvnBuild() {
    try {
            stage('Maven Build [[Mandatory]]') {

                // Check if the version is SNAPSHOT or release version
                isSnapshot = (this.config.artifactVersion.contains('SNAPSHOT')) ? true : false

                artifact = "${this.config.artifactId}-${this.config.artifactVersion}.zip"
                println(artifact);
                if (isSnapshot == true) {
                    sh """
                         export JAVA_HOME=/build_tools/jdk1.8.0_181
                         /build_tools/apache-maven-3.3.3/bin/mvn -U clean install -DskipTests -DgroupId=${this.config.groupId} -DartifactId=${this.config.artifactId} -Dversion=${this.config.artifactVersion} -DgeneratePom=true -DrepositoryId=dsnexus-snapshots -Dpackaging=zip -Durl=https://dsnexus.uk.hibm.hsbc:8081/nexus/content/repositories/snapshots
                    """
                } else {
                    sh """
                         export JAVA_HOME=/build_tools/jdk1.8.0_181
                         /build_tools/apache-maven-3.3.3/bin/mvn -U clean install -DskipTests -DgroupId=${this.config.groupId} -DartifactId=${this.config.artifactId} -Dversion=${this.config.artifactVersion} -DgeneratePom=true -DrepositoryId=dsnexus -Dpackaging=zip -Durl=-Durl=https://dsnexus.uk.hibm.hsbc:8081/nexus/content/repositories/releases
                    """
                }
            }

        }
    catch (Exception e) {
        throw new Exception("ERROR- in building package ", e)
    }
}

private def deployFromNexus2ToGKE() {
	def artifactURL = config.artifactURL
    dir(unzipDir) {

	        sh """
	            cp ../cmb-digital-onboarding-honeycomb-build-settings/cmb-digital-onboarding-ddapi/GKE/DEV/* .
	        """
    		}
	    	this.buildDockerImage()
	    	this.uploadDockerImageToNexus3()
	}
}

private def buildDockerImage() {
    dir(workspaceDir) {
        docker = new Docker()
        //docker.init(mvn_version, jdk_version, workspaceDir)
        //logger.info("inside custom workspace ${workspaceDir}")

        stage('Deploy to nexus') {
            logger.trace("Docker Image Build Stage Start")
            logger.info("intial dockerFilePath: ${dockerFilePath}")
            withCredentials([usernamePassword(credentialsId: 'ALM_NEXUS3_CREDS', passwordVariable: 'PASSWORD', usernameVariable: 'USER')]) {
            //docker.buildImage(dockerFilePath)
                sh """
			docker login ${nexus3DockerRestrictedRepoBase} --username ${env.USER} --password ${env.PASSWORD}
			#docker rmi \$(docker images -a -q)
		"""
                buildOutput = sh (
                    script: "docker build --tag ${tagName} ${dockerFilePath}",
                    returnStdout: true
                ).trim()
                println (buildOutput)
                def buildOutputLines = buildOutput.split('\n')
                def imageId = ''
                for (int i = 1; i < buildOutputLines.length; i++) {
                        if (buildOutputLines[i] =~ /Successfully built/) {
                            strMap = buildOutputLines[i].split(' ')
                            imageId = strMap[2].trim()
                        }
                }
                println("Image ID: ${imageId}")
            logger.trace("Docker Image Build Stage Complete")
        }
    }
}

private def uploadDockerImageToNexus3() {
    def nexus3DockerRepoBase = env.NEXUS3_DOCKER_REPO_BASE
    stage("Docker: Publish Image to Nexus3 DEV") {
        logger.info("Docker Image Nexus Stage Start")
        //dockerNexus3Tag = utils().createNexus3Tag("docker", docker.getGroupId(), docker.getArtefactId(), docker.getVersion())
        logger.info("dockerNexus3Tag:" + tagName )
        withCredentials([usernamePassword(credentialsId: 'ALM_NEXUS3_CREDS', passwordVariable: 'PASSWORD', usernameVariable: 'USER')]) {
            //docker.publishDockerImageToNexus3DevStaging(dockerNexus3Tag)
            sh """docker login ${nexus3DockerRepoBase} --username ${env.USER} --password ${env.PASSWORD}"""
            sh """docker push $tagName"""
            logger.trace("Docker: Publish Image to Nexus 3 Repository Complete")
        }
    }
}
