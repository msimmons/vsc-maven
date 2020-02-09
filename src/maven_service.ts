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
    refreshLock: Promise<boolean>

    private triggerRefresh = async (uri: vscode.Uri) => {
        if (uri.scheme === 'file') {
            await this.refresh(uri)
        }
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

    public async connect() {
        vscode.window.withProgress({location: vscode.ProgressLocation.Window, title: 'VSC-Maven'}, async (progress) => {
            progress.report({message: `Maven: Connecting to ${this.projectDir}`})
            let reply = await this.jvmcode.send('maven.connect', { projectDir: this.projectDir, extensionDir: this.extensionDir})
            this.tasks = reply.body.tasks
            if (reply.body.errors.length) {
                vscode.window.showErrorMessage(`Errors connecting to maven: ${reply.body.errors}`)
            }
        })
    }


    public async refresh(triggerUri? : vscode.Uri) {
        if (this.refreshLock) await this.refreshLock
        let path = triggerUri ? triggerUri.path : this.projectDir
        this.refreshLock = new Promise<boolean>(async (resolve, reject) => {
            vscode.window.withProgress({location: vscode.ProgressLocation.Window, title: 'VSC-Maven'}, async (progress) => {
                progress.report({message: `Maven: Refreshing ${path}`})
                try {
                    let reply = await this.jvmcode.send('maven.refresh', { })
                    this.tasks = reply.body.tasks
                    if (reply.body.errors.length) {
                        vscode.window.showErrorMessage(`Errors refreshing maven: ${reply.body.errors}`)
                    }
                }
                finally {
                    resolve(true)
                }
            })
        })
    }

    public async runTask(task: string, progress: vscode.Progress<{message?: string}>) {
        progress.report({message: 'Starting '+task})
        let reply = await this.jvmcode.send('maven.run-task', { task: task })
        return reply.body
    }

}