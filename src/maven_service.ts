'use strict';

import * as vscode from 'vscode'

export class MavenService {

    projectDir: string
    extensionDir: string
    jvmcode: any
    tasks: string[]
    dependencies: any[]
    classpath: any[]
    problems: vscode.DiagnosticCollection
    watcher: vscode.FileSystemWatcher

    private triggerRefresh = (e: vscode.Uri) => {
        // TODO debounce
        vscode.window.withProgress({ location: vscode.ProgressLocation.Window, title: 'Refresh' }, (progress) => {
            return this.refresh(progress)
        })
    }

    constructor(projectDir: string, extensionDir: string, jvmcode: any) {
        this.projectDir = projectDir
        this.extensionDir = extensionDir
        this.jvmcode = jvmcode
        this.problems = vscode.languages.createDiagnosticCollection("vsc-maven")
        // TODO extensions are configurable
        let pattern = vscode.workspace.rootPath+"/**/pom.xml"
        this.watcher = vscode.workspace.createFileSystemWatcher(pattern)
        this.watcher.onDidChange(this.triggerRefresh)
        this.watcher.onDidDelete(this.triggerRefresh)
    }

    public async connect(progress: vscode.Progress<{message?: string}>) : Promise<any> {
        progress.report({message: 'Maven: Connecting to '+this.projectDir})
        let reply = await this.jvmcode.send('maven.connect', { projectDir: this.projectDir, extensionDir: this.extensionDir })
        this.tasks = reply.body.tasks
        this.dependencies = reply.body.dependencies
        this.classpath = reply.body.classpath
        return reply.body
    }

    public async refresh(progress: vscode.Progress<{message?: string}>) : Promise<any> {
        progress.report({message: 'Maven: Refreshing '+this.projectDir})
        let reply = await this.jvmcode.send('maven.refresh', { })
        this.tasks = reply.body.tasks
        this.dependencies = reply.body.dependencies
        this.classpath = reply.body.classpath
        return reply.body
    }

    public async runTask(task: string, progress: vscode.Progress<{message?: string}>) {
        progress.report({message: 'Starting '+task})
        let reply = await this.jvmcode.send('maven.run-task', { task: task })
        return reply.body
    }

}