export type ApiKey = {
	id: string;
	name: string;
	createdAt: string;
	revokedAt: string | null;
};

export type ApiKeyCreated = {
	id: string;
	name: string;
	rawKey: string;
	createdAt: string;
};

export type ApiKeyPage = {
	content: ApiKey[];
	page: number;
	size: number;
	totalElements: number;
	totalPages: number;
};
