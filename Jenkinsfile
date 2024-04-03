pipeline {
  agent { label 'ecs' }
  options {
    buildDiscarder(logRotator(numToKeepStr: '10'))
  }
  environment {
    AWS_CREDENTIALS = credentials('AWS-KEYS')
    AWS_ENV = """${sh(
      returnStdout: true,
      script: 'env | awk -F= \'/^AWS/ {print "-e " $1}\''
    )}"""
    GIT_ENV = """${sh(
      returnStdout: true,
      script: 'env | awk -F= \'/^GIT/ {print "-e " $1}\''
    )}"""
  }
  stages {
    stage('Setup') {
      steps {
        sh '''
          docker pull $CICD_ECR_REGISTRY/cicd:latest
          docker tag $CICD_ECR_REGISTRY/cicd:latest cicd:latest
        '''
      }
    }
    stage('Build') {
      steps {
        echo 'Building Docker Image'
        sh 'docker build -t hapi-fhir-jpaserver-starter .'
      }
    }
    stage('Push') {
      steps {
        echo 'Pushing Docker Image'
        sh 'docker run -v /var/run/docker.sock:/var/run/docker.sock $AWS_ENV $GIT_ENV cicd push hapi-fhir-jpaserver-starter'
      }
    }
    stage('Deploy') {
      steps {
        echo 'Deploying to QA'
        sh 'docker run -v /var/run/docker.sock:/var/run/docker.sock $AWS_ENV $GIT_ENV cicd deploy hapi-fhir-jpaserver-r4 qa $GIT_COMMIT'
        }
    }
    stage('Wait') {
      steps {
        echo 'Waiting for QA service to reach steady state'
        sh 'docker run -v /var/run/docker.sock:/var/run/docker.sock $AWS_ENV $GIT_ENV cicd wait hapi-fhir-jpaserver-r4 qa'
        }
    }
    stage('Healthcheck') {
      steps {
        echo 'Checking health of QA service'
        sh 'curl -m 10 https://fhir4-qa.elimuinformatics.com/actuator/health'  
      }
    }
  }
  post {
    unsuccessful {
      slackSend color: 'danger', channel: '#product-ops-qa', message: "Pipeline Failed: ${env.JOB_NAME} ${env.BUILD_NUMBER} (<${env.BUILD_URL}|Open>)"
    }
    fixed {
      slackSend color: 'good', channel: '#product-ops-qa', message: "Pipeline Ran Successfully: ${env.JOB_NAME} ${env.BUILD_NUMBER} (<${env.BUILD_URL}|Open>)"
    }
  }
}