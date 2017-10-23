#!/usr/bin/groovy

package io.estrado;

def kubectlTest() {
    // Test that kubectl can correctly communication with the Kubernetes API
    println "checking kubectl connnectivity to the API"
    sh "kubectl get nodes"
}

def genAWSCliCreds() {
   def aws_creds = '[default]' + "\naws_access_key_id = $AWS_ACCESS_KEY\naws_secret_access_key = $AWS_SECRET_KEY"
   def aws_config = '[default]' + "\noutput = json\nregion = $AWS_REGION"
   sh """
        mkdir -p .aws/
        echo  \"${aws_creds}\"  > .aws/credentials
        chmod 400 .aws/credentials
        echo  \"${aws_config}\"  > .aws/config
        chmod 400 .aws/config
      """
}

def genNexusRepoConf() {
      def sbt_repos= '[repositories]\nlocal\nivy-proxy-releases: http://nexus.burnerapp.com:8080/content/groups/ivy-releases/, [organization]/[module]/(scala_[scalaVersion]/)(sbt_[sbtVersion]/)[revision]/[type]s/[artifact](-[classifier]).[ext]\nmaven-proxy-releases: http://nexus.burnerapp.com:8080/content/groups/public/\nmaven-private-snapshots: http://nexus.burnerapp.com:8080/content/repositories/snapshots/\nmaven-private-releases:  http://nexus.burnerapp.com:8080/content/repositories/releases/\nsonatype-proxy-releases: http://nexus.burnerapp.com:8080/repositories/sonatype_public/, [organization]/[module]/[revision]/[type]s/[artifact](-[classifier]).[ext]\ncenter: https://jcenter.bintray.com/\nmaven-central-proxy: http://nexus.burnerapp.com:8080/content/repositories/central/\ntypesafe-ivy-releases: https://repo.typesafe.com/typesafe/ivy-releases/, [organization]/[module]/[revision]/[type]s/[artifact](-[classifier]).[ext], bootOnly\nsbt-ivy-snapshots: https://repo.scala-sbt.org/scalasbt/ivy-snapshots/, [organization]/[module]/[revision]/[type]s/[artifact](-[classifier]).[ext], bootOnly'
      def sbt_creds_plugin = 'credentials += Credentials(Path.userHome / \\".sbt\\" / \\".credentials\\")'
      def sbt_creds = "realm=Sonatype Nexus Repository Manager\nhost=nexus.burnerapp.com\nuser=$REPO_USER\npassword=$REPO_PASSWORD"
      println("Gen sbt nexus repo creds")
      sh """
        mkdir -p .sbt/0.13/plugins
        echo  \"${sbt_repos}\"  > .sbt/repositories
        echo  \"${sbt_creds}\" > .sbt/.credentials
        echo  \"${sbt_creds_plugin}\" > .sbt/0.13/plugins/plugins.sbt
      """
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
      def pub_cmd = 'curl -i -X PUT -F repo=stable  -F chart=@' + "${args.file} ${args.url}"
      sh "${cmd}"
}

def gitCommit(Map args) {
        println("commiting to github")
        sshagent (credentials:["${args.creds_id}"]) {
          sh """
            git checkout ${args.branch}
            git config user.email \"${args.git_user_email}\"
            git config user.name \"${args.git_user}\"
            git config push.default simple
            git add ${args.chart}/values.yaml ${args.chart}/Chart.yaml
            git commit -m \"Updating ${args.app_name}.image.tag to $args.commit_id\"
            git status
            git push origin ${args.branch}
          """
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

def sbtInitDockerContainer() {
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

def sbtCompileAndTest(Map args) {
  sh "${args.mysbt} compile"
  sh "${args.mysbt} test:compile"
}

def sbtTests(Map args) {
  sh "${args.mysbt} scalastyle" 
  if (args.test) {
      println 'sbt test'
      sh "${args.mysbt}  -Dspecs2.timeFactor=3 test"
      sh 'mkdir -p junit && find . -type f -regex ".*/target/test-reports/.*xml" -exec cp {} junit/ \\;'
      junit allowEmptyResults: true, testResults: 'junit/*.xml'
  }
}

def sbtBuildAndPush(Map args) {
      sh "${args.mysbt} package"
      sh "${args.mysbt} docker"
      sh "${args.mysbt} dockerPush"
}

def sbtEBPublish(Map args) {
      sh '/usr/bin/env'
      sh "find target/scala* -name \"*.war\" -type f |  xargs -n 1 sh -c \'echo \$0\'"
      sh "aws s3 ls s3://${params.s3Bucket}/${params.appName}"
      echo "\'aws s3 cp output_from_find_above  s3://${params.s3Bucket}/${params.appName}/\'"
      sh "find target/scala* -name \"*.war\" -type f -exec basename {} \\; |  xargs -n 1 sh -c \'echo \$0\'"
      echo "aws elasticbeanstalk create-application-version --application-name ${params.deployment}-${params.appName}"
      echo " --version-label ${params.buildNum}-${params.commitId} --source-bundle S3Bucket=\"${params.s3Bucket}\",S3Key=\"${params.appName}/\\$0\""
      echo " --description JenkinsBuild:${params.branch}:${params.buildNum} --no-auto-create-application\'"
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
