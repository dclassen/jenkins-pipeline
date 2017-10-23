#!/usr/bin/groovy

package io.estrado;

def mysbt = 'java -Xms512M -Xmx1536M -Xss1M -XX:+CMSClassUnloadingEnabled -jar /sbt/sbt-launch.jar'

def kubectlTest() {
    // Test that kubectl can correctly communication with the Kubernetes API
    println "checking kubectl connnectivity to the API"
    sh "kubectl get nodes"
}

// setup helm connectivity to Kubernetes API and Tiller

def helmInit() {
   println "initiliazing helm client only"
   sh "helm init --client-only"
} 

def helmLint(String chart_dir) {
    println "running helm lint ${chart_dir}"
    sh "helm lint ${chart_dir}"
}

def helmConfig() {
    println "checking client/server version"
    sh "helm version"
}


def helmDeploy(Map args) {
    //configure helm client and confirm tiller process is installed
    helmConfig()
    def String namespace
    // If namespace isn't parsed into the function set the namespace to the name
    if (args.namespace == null) {
        namespace = args.name
    } else {
        namespace = args.namespace
    }
    if (args.dry_run) {
        println "Running dry-run deployment"
        sh "helm upgrade --dry-run --install ${args.name} ${args.chart_dir} --set imageTag=${args.version_tag},replicas=${args.replicas},cpu=${args.cpu},memory=${args.memory},ingress.hostname=${args.hostname} --namespace=${namespace}"
    } else {
        println "Running deployment"
        // reimplement --wait once it works reliable
        sh "helm upgrade --install ${args.name} ${args.chart_dir} --set imageTag=${args.version_tag},replicas=${args.replicas},cpu=${args.cpu},memory=${args.memory},ingress.hostname=${args.hostname} --namespace=${namespace}"
        // sleeping until --wait works reliably
        sleep(20)
        echo "Application ${args.name} successfully deployed. Use helm status ${args.name} to check"
    }
}

def helmDelete(Map args) {
      println "Running helm delete ${args.name}"
      sh "helm delete ${args.name}"
}

def helmTest(Map args) {
      println "Running Helm test"
      sh "helm test ${args.name} --cleanup"
}

def helmPackage(Map args) {
    dir ("${args.repo}") { sh "helm package ${args.chart}" }
}

def helmChartPublisherInit() {
      sh 'apk update && apk add curl'
}

def helmChartPublish(Map args) {
      sh "curl -i -X PUT -F repo=stable  -F chart=@${args.file} ${args.url}"
}

def gitCommit(Map args) {
        println("commiting to github")
        sshagent (credentials:["${args.creds_id}"]) {
          sh "git checkout ${args.branch}"
          sh "git config user.email \"${args.git_user_email}\""
          sh "git config user.name \"${args.git_user}\""
          sh 'git config push.default simple'
          sh "git add ${args.chart}/values.yaml ${args.chart}/Chart.yaml"
          sh "git commit -m \"Updating ${args.app_name}.image.tag to $args.commit_id\""
          sh 'git status'
          sh "git push origin ${args.branch}"
        } // sshagent
}

def gitEnvVars() {
    println "Setting envvars to tag container"
    sh 'git rev-parse HEAD > git_commit_id.txt'
    try {
        env.GIT_COMMIT_ID = readFile('git_commit_id.txt').trim()
        env.GIT_SHA = env.GIT_COMMIT_ID.substring(0, 7)
    } catch (e) {
        error "${e}"
    }
    println "env.GIT_COMMIT_ID ==> ${env.GIT_COMMIT_ID}"
    sh 'git config --get remote.origin.url> git_remote_origin_url.txt'
    try {
        env.GIT_REMOTE_URL = readFile('git_remote_origin_url.txt').trim()
    } catch (e) {
        error "${e}"
    }
    println "env.GIT_REMOTE_URL ==> ${env.GIT_REMOTE_URL}"
}

def sbtInitCreds() {
   
}

def sbtInitDockerContainer() {
  container('docker') {
    sh 'apk update'
    sh 'apk add openjdk8'
    sh 'apk add python'
    sh 'apk add curl'
    sh 'curl https://s3.amazonaws.com/aws-cli/awscli-bundle.zip -o awscli-bundle.zip'
    sh 'unzip awscli-bundle.zip'
    sh './awscli-bundle/install -i /usr/local/aws -b /usr/local/bin/aws'
    sh 'mkdir /sbt && wget -O /sbt/sbt-launch.jar https://repo.typesafe.com/typesafe/ivy-releases/org.scala-sbt/sbt-launch/0.13.13/sbt-launch.jar'
    sh 'echo "java -Xms512M -Xmx1536M -Xss1M -XX:+CMSClassUnloadingEnabled -jar /sbt/sbt-launch.jar "$@"" > /sbt/sbt'
    sh 'chmod u+x /sbt/sbt'
    sh 'cp -r .sbt /root'
    sh 'cp -r .aws /home/jenkins'
  }
}

def sbtCompileAndTest() {
  sh "${mysbt} compile"
  sh "${mysbt} compile:test"
}

def sbtTests() {
  sh "${mysbt} scalastyle" 
  if (config.app.test) {
      println 'sbt test'
      sh "${mysbt}  -Dspecs2.timeFactor=3 test"
      sh 'mkdir -p junit && find . -type f -regex ".*/target/test-reports/.*xml" -exec cp {} junit/ \\;'
      junit allowEmptyResults: true, testResults: 'junit/*.xml'
  }
}

def sbtBuildAndPush() {
      sh "${mysbt} package"
      sh "${mysbt} docker"
      sh "${mysbt} dockerPush"
}

def sbtEBPublish() {
      sh "find target/scala* -name \"*.war\" -type f |  xargs -n 1 sh -c \'echo \$0\'"
      // \'aws s3 cp $0 s3://${config.eb.s3Bucket}/${config.app.name}/\'
      sh "find target/scala* -name \"*.war\" -type f -exec basename {} \\; |  xargs -n 1 sh -c \'echo \$0\'"
      // \'aws elasticbeanstalk create-application-version --application-name ${config.chart.values}-${config.app.name} 
      // --version-label $BUILD_NUM $GIT_COMMIT_ID --source-bundle S3Bucket=\"${config.eb.s3Bucket}\",S3Key=\"${config.app.name}/$0\" 
      // --description JenkinsBuild:$BRANCH_NAME:$BUILD_NUMBER --no-auto-create-application\'
}

@NonCPS
def getMapValues(Map map=[:]) {
    // jenkins and workflow restriction force this function instead of map.values(): https://issues.jenkins-ci.org/browse/JENKINS-27421
    def entries = []
    def map_values = []

    entries.addAll(map.entrySet())

    for (int i=0; i < entries.size(); i++){
        String value =  entries.get(i).value
        map_values.add(value)
    }

    return map_values
}
