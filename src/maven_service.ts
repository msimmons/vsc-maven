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

    private triggerCompile = (e: vscode.Uri) => {
        // TODO debounce
        vscode.window.withProgress({ location: vscode.ProgressLocation.Window, title: 'Compile' }, (progress) => {
            return this.compile(progress)
        })
    }

    constructor(projectDir: string, extensionDir: string, jvmcode: any) {
        this.projectDir = projectDir
        this.extensionDir = extensionDir
        this.jvmcode = jvmcode
        this.problems = vscode.languages.createDiagnosticCollection("vsc-maven")
        // TODO extensions are configurable
        let pattern = vscode.workspace.rootPath+"/**/*.{java,kt,groovy}"
        this.watcher = vscode.workspace.createFileSystemWatcher(pattern)
        this.watcher.onDidChange(this.triggerCompile)
        this.watcher.onDidDelete(this.triggerCompile)
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

    public async compile(progress: vscode.Progress<{message?: string}>) {
        progress.report({message: 'Maven: Compiling'})
        this.problems.clear()
        let reply = await this.jvmcode.send('maven.compile', {})
        reply.body.messages.forEach(problem => {
            let uri = vscode.Uri.file(problem.file)
            let existing = this.problems.get(uri)
            existing = existing ? existing : []
            let range = new vscode.Range(problem.line-1, problem.column-1, problem.line-1, problem.column-1)
            let diag = new vscode.Diagnostic(range, problem.message, vscode.DiagnosticSeverity.Error)
            this.problems.set(uri, existing.concat(diag))
        });
    }

}