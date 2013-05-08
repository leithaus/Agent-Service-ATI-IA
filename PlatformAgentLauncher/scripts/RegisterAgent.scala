import com.ati.iaservices.helpers.{RegisterAgentHelper, CreateDSLHelper, CreateStoreHelper, SetContentHelper}
import com.protegra_ati.agentservices.core.messages.admin.RegistrationResponse
import com.protegra_ati.agentservices.core.schema.util.ConnectionFactory
import com.protegra_ati.agentservices.core.schema.Profile
import java.util.UUID

// START STORE AND DSL PlatformAgents
val store = new CreateStoreHelper().createStore()
val dsl = new CreateDSLHelper().createDSL()

// CREATE AN AGENTSESSION
var agentSessionId: UUID = UUID.randomUUID
val BIZNETWORK_AGENT_ID = UUID.fromString("f5bc533a-d417-4d71-ad94-8c766907381b")

def createProfile(agentId: UUID) {
  val selfCnxn = ConnectionFactory.createSelfConnection("", agentId.toString)

  val profile = new Profile()
  profile.setFirstName("John")
  profile.setLastName("Smith")
  profile.setCity("Winnipeg")
  profile.setCountry("Canada")

  val setContentHelper = new SetContentHelper[Profile]() {
    def handleListen(profile: Profile) {
      println("*************** Found Profile Data ***************")
      println(profile)
    }
  }
  val tag = "SetProfile" + UUID.randomUUID()
  setContentHelper.listen(dsl, agentSessionId, tag)
  setContentHelper.request(dsl, agentSessionId, tag, profile, selfCnxn.writeCnxn)
}

// REGISTER A NEW AGENT
val registerAgentHelper = new RegisterAgentHelper() {
  def handleListen(response: RegistrationResponse) {
    println("*************** Found RegistrationResponse Data ***************")
    println(response)
    println("*************** New AgentId = " + response.agentId + " ***************")
    createProfile(response.agentId)
  }
}

val tag = "Register" + UUID.randomUUID
registerAgentHelper.listen(dsl, agentSessionId, tag)
registerAgentHelper.request(dsl, agentSessionId, tag, BIZNETWORK_AGENT_ID, "John Smith")
