export type WebhookEndpoint = {
	id: string;
	name: string;
	url: string;
	enabled: boolean;
	createdAt: string;
	updatedAt: string;
};

export type WebhookEndpointPage = {
	content: WebhookEndpoint[];
	page: number;
	size: number;
	totalElements: number;
	totalPages: number;
};

export type WebhookEndpointCreated = WebhookEndpoint & {
	signingSecret: string;
};

export type WebhookSecretRotated = {
	id: string;
	signingSecret: string;
	previousSigningSecretExpiresAt: string;
};
