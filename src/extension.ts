'use strict';
// The module 'vscode' contains the VS Code extensibility API
// Import the module and reference it with the alias vscode in your code below
import * as vscode from 'vscode';
import { ProgressLocation } from 'vscode'
import { MavenService } from './maven_service'
import { readdirSync } from 'fs';

let jvmcode
let mavenService: MavenService

// this method is called when your extension is activated
// your extension is activated the very first time the command is executed
export function activate(context: vscode.ExtensionContext) {

    jvmcode = vscode.extensions.getExtension('contrapt.jvmcode').exports

    installVerticle()

    function installVerticle() {
        let jarFiles = findJars().map((j) => { return context.asAbsolutePath('out/' + j) })
        jvmcode.install(jarFiles, 'net.contrapt.maven.MavenVerticle').then((result) => {
            registerProviders()
            connectMaven()
        }).catch((error) => {
            vscode.window.showErrorMessage('Error starting maven service: ' + error.message)
        })
    }

    function findJars() {
        let files = []
        let dir = context.asAbsolutePath('out')
        readdirSync(dir).forEach((entry) => {
            if (entry.endsWith('.jar')) {
                files.push(entry)
            }
        })
        return files
    }

    function registerProviders() {
        // Do we have a task provider??
    }

    function connectMaven() {
        mavenService = new MavenService(vscode.workspace.rootPath, context.extensionPath, jvmcode)
        mavenService.connect()
    }

    context.subscriptions.push(vscode.commands.registerCommand('maven.refresh', () => {
        mavenService.refresh()
    }))

    context.subscriptions.push(vscode.commands.registerCommand('maven.run-task', () => {
        vscode.window.showQuickPick(mavenService.tasks, {}).then((choice) => {
            // TODO Allow mutliple task choices
            if (!choice) return
            // TODO Actually show progress
            vscode.window.withProgress({ location: ProgressLocation.Window, title: choice }, (progress) => {
                return mavenService.runTask(choice, progress).catch((error) => {
                    vscode.window.showErrorMessage('Error running task: ' + error.message)
                })
            })
        })
    }))

}

// this method is called when your extension is deactivated
export function deactivate() {
    console.log('Closing all the things')
}   