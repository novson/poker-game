pipeline {
    agent any

    tools {
        jdk 'jdk17'
        maven 'maven3'
        nodejs 'node22'
    }

    triggers {
        githubPush()
    }

    options {
        timestamps()
        disableConcurrentBuilds()
        timeout(time: 20, unit: 'MINUTES')
        buildDiscarder(logRotator(numToKeepStr: '20'))
    }

    environment {
        CI = 'true'
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        stage('Backend test') {
            steps {
                dir('backend') {
                    sh 'mvn -B clean verify'
                }
            }
            post {
                always {
                    junit allowEmptyResults: true, testResults: 'backend/target/surefire-reports/*.xml'
                }
            }
        }

        stage('Frontend test & build') {
            steps {
                dir('frontend') {
                    sh 'npm ci'
                    sh 'npm test -- --passWithNoTests'
                    sh 'npm run build'
                }
            }
        }

        stage('Package') {
            steps {
                archiveArtifacts artifacts: 'backend/target/*.jar,frontend/dist/**', fingerprint: true
            }
        }
    }

    post {
        success { echo "CI passed for ${env.GIT_COMMIT}" }
        failure { echo "CI failed. Fix checks before merging." }
        always { deleteDir() }
    }
}

