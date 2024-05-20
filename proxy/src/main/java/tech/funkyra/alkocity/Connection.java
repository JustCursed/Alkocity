package tech.funkyra.alkocity;

import io.grpc.Channel;
import io.grpc.ManagedChannelBuilder;
import tech.funkyra.alkocity.protos.CommandsGrpc;
import tech.funkyra.alkocity.protos.CommandsGrpc.CommandsFutureStub;
import tech.funkyra.alkocity.protos.InformationGrpc;
import tech.funkyra.alkocity.protos.InformationGrpc.InformationFutureStub;

public class Connection {
	private final static Channel channel = ManagedChannelBuilder.forTarget("localhost:8080").usePlaintext().build();

	public final static InformationFutureStub inf = InformationGrpc.newFutureStub(channel);
	public final static CommandsFutureStub cmds = CommandsGrpc.newFutureStub(channel);
}
