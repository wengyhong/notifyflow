export type Workspace = {
	tenantId: string;
	name: string;
	role: 'ADMIN' | 'VIEWER';
};

export type CurrentUser = {
	id: string;
	email: string;
	workspaces: Workspace[];
};