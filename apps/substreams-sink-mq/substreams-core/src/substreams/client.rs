use std::{fmt::Display, sync::Arc, time::Duration};

use http::{uri::Scheme, Uri};
use tonic::{
    codec::CompressionEncoding,
    codegen::http,
    metadata::MetadataValue,
    transport::{Channel, ClientTlsConfig},
};

use crate::pb::sf::substreams::rpc::v2::stream_client::StreamClient;
use crate::pb::sf::substreams::rpc::v2::{Request, Response};

#[derive(Clone, Debug)]
pub struct SubstreamsEndpoint {
    pub uri: String,
    pub token: Option<String>,
    channel: Channel,
}

impl Display for SubstreamsEndpoint {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        Display::fmt(self.uri.as_str(), f)
    }
}

impl SubstreamsEndpoint {
    pub async fn new<S: AsRef<str>>(url: S, token: Option<String>) -> Result<Self, anyhow::Error> {
        let uri = url.as_ref().parse::<Uri>().expect("validated Uri");

        let endpoint = match uri.scheme().unwrap_or(&Scheme::HTTP).as_str() {
            "http" => Channel::builder(uri),
            "https" => Channel::builder(uri)
                .tls_config(ClientTlsConfig::new().with_native_roots())
                .expect("TLS config on this host is invalid"),
            _ => panic!("invalid uri scheme for firehose endpoint"),
        }
        .connect_timeout(Duration::from_secs(10))
        .tcp_keepalive(Some(Duration::from_secs(30)));

        let uri = endpoint.uri().to_string();
        let channel = endpoint.connect_lazy();

        Ok(SubstreamsEndpoint {
            uri,
            channel,
            token,
        })
    }

    pub async fn substreams(
        self: Arc<Self>,
        request: Request,
    ) -> Result<tonic::Streaming<Response>, anyhow::Error> {
        let token_metadata: Option<MetadataValue<tonic::metadata::Ascii>> = match self.token.clone()
        {
            Some(token) => Some(token.as_str().try_into()?),
            None => None,
        };

        let mut client = StreamClient::with_interceptor(
            self.channel.clone(),
            move |mut req: tonic::Request<()>| {
                if let Some(ref token) = token_metadata {
                    req.metadata_mut().insert("authorization", token.clone());
                }

                // We increase the receiving message size limit to 50MB, since some
                // blocks might be quite big. We've seen up to 30MB blocks in BSC.
                req.metadata_mut().insert(
                    "grpc-max-receive-message-length",
                    "52428800".try_into().unwrap(),
                );

                Ok(req)
            },
        );

        client = client
            .send_compressed(CompressionEncoding::Gzip)
            .accept_compressed(CompressionEncoding::Gzip);

        let response_stream = client.blocks(request).await?;
        let inner_stream = response_stream.into_inner();

        Ok(inner_stream)
    }
}
